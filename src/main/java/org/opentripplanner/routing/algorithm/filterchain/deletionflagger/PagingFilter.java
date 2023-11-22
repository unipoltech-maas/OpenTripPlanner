package org.opentripplanner.routing.algorithm.filterchain.deletionflagger;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.ItinerarySortKey;
import org.opentripplanner.model.plan.pagecursor.ItineraryPageCut;
import org.opentripplanner.routing.algorithm.filterchain.comparator.SortOrderComparator;

/**
 * This class is used to enforce the cut/limit between two pages. It removes potential duplicates
 * and keep missed itineraries. It uses information from the page cursor to determine which
 * itineraries are potential duplicates and missed ones.
 * <p>
 * Based on where the previous results were cropped the potential duplicates will appear either at
 * the top of the list, or the bottom. If the previous results were cropped at the top, then the
 * potential duplicates will appear at the bottom of the list. If the previous results were cropped
 * at the bottom, then the potential duplicates will appear at the top of the list.
 */
public class PagingFilter implements ItineraryDeletionFlagger {

  public static final String TAG = "paging-filter";

  private final ItineraryPageCut itineraryPageCut;
  private final Comparator<ItinerarySortKey> sortOrderComparator;

  public PagingFilter(ItineraryPageCut itineraryPageCut) {
    this.itineraryPageCut = itineraryPageCut;
    this.sortOrderComparator = SortOrderComparator.comparator(itineraryPageCut.sortOrder());
  }

  @Override
  public String name() {
    return TAG;
  }

  private boolean sortsIntoDeduplicationAreaRelativeToRemovedItinerary(Itinerary itinerary) {
    return switch (itineraryPageCut.deduplicationSection()) {
      case HEAD -> sortOrderComparator.compare(itinerary, itineraryPageCut) < 0;
      case TAIL -> sortOrderComparator.compare(itinerary, itineraryPageCut) > 0;
    };
  }

  @Override
  public List<Itinerary> flagForRemoval(List<Itinerary> itineraries) {
    return itineraries
      .stream()
      .filter(it ->
        (
          it.startTime().toInstant().isAfter(itineraryPageCut.windowStart()) &&
          it.startTime().toInstant().isBefore(itineraryPageCut.windowEnd()) &&
          sortsIntoDeduplicationAreaRelativeToRemovedItinerary(it)
        )
      )
      .collect(Collectors.toList());
  }
}