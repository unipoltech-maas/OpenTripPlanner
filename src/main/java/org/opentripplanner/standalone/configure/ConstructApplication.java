package org.opentripplanner.standalone.configure;

import io.micrometer.core.instrument.Metrics;
import javax.annotation.Nullable;
import javax.ws.rs.core.Application;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.ext.geocoder.LuceneIndex;
import org.opentripplanner.ext.transmodelapi.TransmodelAPI;
import org.opentripplanner.graph_builder.GraphBuilder;
import org.opentripplanner.graph_builder.GraphBuilderDataSources;
import org.opentripplanner.routing.algorithm.astar.TraverseVisitor;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitLayer;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.TransitLayerMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.TransitLayerUpdater;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.config.BuildConfig;
import org.opentripplanner.standalone.config.CommandLineParameters;
import org.opentripplanner.standalone.config.ConfigModel;
import org.opentripplanner.standalone.config.OtpConfig;
import org.opentripplanner.standalone.config.RouterConfig;
import org.opentripplanner.standalone.server.DefaultServerContext;
import org.opentripplanner.standalone.server.GrizzlyServer;
import org.opentripplanner.standalone.server.MetricsLogging;
import org.opentripplanner.standalone.server.OTPWebApplication;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.raptor.configure.RaptorConfig;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.updater.GraphUpdaterConfigurator;
import org.opentripplanner.util.OTPFeature;
import org.opentripplanner.visualizer.GraphVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for creating the top level services like the {@link OTPWebApplication}
 * and {@link GraphBuilder}. The purpose of this class is to wire the application, creating the
 * necessary Services and modules and putting them together. It is NOT responsible for starting or
 * running the application. The whole idea of this class is to separate application construction
 * from running it.
 * <p>
 * The top level construction class(this class) may delegate to other construction classes
 * to inject configuration and services into sub-modules. An instance of this class is created
 * using the {@link LoadApplication} - A application is constructed AFTER config and input files
 * are loaded.
 * <p>
 * THIS CLASS IS NOT THREAD SAFE - THE APPLICATION SHOULD BE CREATED IN ONE THREAD. This
 * should be really fast, since the only IO operations are reading config files and logging. Loading
 * transit or map data should NOT happen during this phase.
 */
public class ConstructApplication {

  private static final Logger LOG = LoggerFactory.getLogger(ConstructApplication.class);

  private final CommandLineParameters cli;
  private final GraphBuilderDataSources graphBuilderDataSources;
  private final ConfigModel config;
  private final Graph graph;
  private final TransitModel transitModel;
  private final ConstructApplicationFactory factory;

  /* Lazy initialized fields */
  private DefaultServerContext context;

  /**
   * Create a new OTP configuration instance for a given directory.
   */
  ConstructApplication(
    CommandLineParameters cli,
    Graph graph,
    TransitModel transitModel,
    ConfigModel config,
    GraphBuilderDataSources graphBuilderDataSources
  ) {
    this.cli = cli;
    this.config = config;
    this.graph = graph;
    this.transitModel = transitModel;
    this.graphBuilderDataSources = graphBuilderDataSources;
    this.factory =
      DaggerConstructApplicationFactory.builder().configModel(config).graph(graph).build();
  }

  public ConstructApplicationFactory getFactory() {
    return factory;
  }

  /**
   * After the graph and transitModel is read from file or build, then it should be set here,
   * so it can be used during construction of the web server.
   */
  public DefaultServerContext serverContext() {
    if (context == null) {
      this.context =
        DefaultServerContext.create(
          routerConfig(),
          raptorConfig(),
          graph,
          transitModel,
          Metrics.globalRegistry,
          traverseVisitor()
        );
    }
    return context;
  }

  /**
   * Create a new Grizzly server - call this method once, the new instance is created every time
   * this method is called.
   */
  public GrizzlyServer createGrizzlyServer() {
    return new GrizzlyServer(cli, createApplication());
  }

  /**
   * Create the default graph builder.
   */
  public GraphBuilder createGraphBuilder() {
    LOG.info("Wiring up and configuring graph builder task.");
    return GraphBuilder.create(
      buildConfig(),
      graphBuilderDataSources,
      graph(),
      transitModel(),
      cli.doLoadStreetGraph(),
      cli.doSaveStreetGraph()
    );
  }

  /**
   * The output data source to use for saving the serialized graph.
   * <p>
   * This method will return {@code null} if the graph should NOT be saved. The business logic to
   * make that decision is in the {@link GraphBuilderDataSources}.
   */
  @Nullable
  public DataSource graphOutputDataSource() {
    return graphBuilderDataSources.getOutputGraph();
  }

  private Application createApplication() {
    LOG.info("Wiring up and configuring server.");
    setupTransitRoutingServer();
    return new OTPWebApplication(() -> serverContext().createHttpRequestScopedCopy());
  }

  public GraphVisualizer graphVisualizer() {
    return cli.visualize ? factory.graphVisualizer() : null;
  }

  public TraverseVisitor traverseVisitor() {
    var gv = graphVisualizer();
    return gv == null ? null : gv.traverseVisitor;
  }

  private void setupTransitRoutingServer() {
    new MetricsLogging(transitModel(), raptorConfig());

    creatTransitLayerForRaptor(transitModel(), routerConfig());

    /* Create Graph updater modules from JSON config. */
    GraphUpdaterConfigurator.setupGraph(graph(), transitModel(), routerConfig().updaterConfig());

    graph().initEllipsoidToGeoidDifference();

    if (OTPFeature.SandboxAPITransmodelApi.isOn()) {
      TransmodelAPI.setUp(
        routerConfig().transmodelApi(),
        transitModel(),
        routerConfig().routingRequestDefaults()
      );
    }

    if (OTPFeature.SandboxAPIGeocoder.isOn()) {
      LOG.info("Creating debug client geocoder lucene index");
      LuceneIndex.forServer(serverContext());
    }
  }

  /**
   * Create transit layer for Raptor routing. Here we map the scheduled timetables.
   */
  public static void creatTransitLayerForRaptor(
    TransitModel transitModel,
    RouterConfig routerConfig
  ) {
    if (!transitModel.hasTransit() || transitModel.getTransitModelIndex() == null) {
      LOG.warn(
        "Cannot create Raptor data, that requires the graph to have transit data and be indexed."
      );
    }
    LOG.info("Creating transit layer for Raptor routing.");
    transitModel.setTransitLayer(
      TransitLayerMapper.map(routerConfig.transitTuningParameters(), transitModel)
    );
    transitModel.setRealtimeTransitLayer(new TransitLayer(transitModel.getTransitLayer()));
    transitModel.setTransitLayerUpdater(
      new TransitLayerUpdater(
        transitModel,
        transitModel.getTransitModelIndex().getServiceCodesRunningForDate()
      )
    );
  }

  public TransitModel transitModel() {
    return transitModel;
  }

  public Graph graph() {
    return graph;
  }

  public Deduplicator deduplicator() {
    return transitModel().getDeduplicator();
  }

  public OtpConfig otpConfig() {
    return config.otpConfig();
  }

  public RouterConfig routerConfig() {
    return config.routerConfig();
  }

  public BuildConfig buildConfig() {
    return config.buildConfig();
  }

  public RaptorConfig<TripSchedule> raptorConfig() {
    return factory.raptorConfig();
  }
}
