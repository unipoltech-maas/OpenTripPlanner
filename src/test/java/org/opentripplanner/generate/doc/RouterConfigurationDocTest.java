package org.opentripplanner.generate.doc;

import static org.opentripplanner.framework.io.FileUtils.assertFileEquals;
import static org.opentripplanner.framework.io.FileUtils.readFile;
import static org.opentripplanner.framework.io.FileUtils.writeFile;
import static org.opentripplanner.framework.text.MarkdownFormatter.HEADER_3;
import static org.opentripplanner.generate.doc.framework.TemplateUtil.replaceParametersDetails;
import static org.opentripplanner.generate.doc.framework.TemplateUtil.replaceParametersTable;
import static org.opentripplanner.standalone.config.framework.JsonSupport.jsonNodeFromResource;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.opentripplanner.generate.doc.framework.ParameterDetailsList;
import org.opentripplanner.generate.doc.framework.ParameterSummaryTable;
import org.opentripplanner.generate.doc.framework.SkipNodes;
import org.opentripplanner.standalone.config.RouterConfig;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

public class RouterConfigurationDocTest {

  private static final File TEMPLATE = new File("doc-templates", "RouterConfiguration.md");
  private static final File OUT_FILE = new File("docs", "RouterConfiguration.md");

  private static final String BUILD_CONFIG_FILENAME = "standalone/config/router-config.json";
  private static final SkipNodes SKIP_NODES = SkipNodes.of(
    "vectorTileLayers",
    "/docs/sandbox/MapboxVectorTilesApi.md",
    "routingDefaults",
    "/docs/RouteRequest.md",
    "updaters",
    "/docs/UpdaterConfig.md"
  );

  /**
   * NOTE! This test updates the {@code docs/Configuration.md} document based on the latest
   * version of the code. The following is auto generated:
   * <ul>
   *   <li>The configuration type table</li>
   *   <li>The list of OTP features</li>
   * </ul>
   */
  @Test
  public void updateBuildConfigurationDoc() {
    NodeAdapter node = readBuildConfig();

    // Read and close inout file (same as output file)
    String doc = readFile(TEMPLATE);
    String original = readFile(OUT_FILE);

    doc = replaceParametersTable(doc, getParameterSummaryTable(node));
    doc = replaceParametersDetails(doc, getParameterDetailsTable(node));

    writeFile(OUT_FILE, doc);

    assertFileEquals(original, OUT_FILE);
  }

  private NodeAdapter readBuildConfig() {
    var json = jsonNodeFromResource(BUILD_CONFIG_FILENAME);
    var conf = new RouterConfig(json, BUILD_CONFIG_FILENAME, false);
    return conf.asNodeAdapter();
  }

  private String getParameterSummaryTable(NodeAdapter node) {
    return new ParameterSummaryTable(SKIP_NODES).createTable(node).toMarkdownTable();
  }

  private String getParameterDetailsTable(NodeAdapter node) {
    return ParameterDetailsList.listParametersWithDetails(node, SKIP_NODES, HEADER_3);
  }
}
