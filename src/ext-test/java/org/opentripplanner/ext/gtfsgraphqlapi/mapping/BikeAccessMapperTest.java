package org.opentripplanner.ext.gtfsgraphqlapi.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.network.BikeAccess;

class BikeAccessMapperTest {

  @Test
  void mapping() {
    Arrays
      .stream(BikeAccess.values())
      .filter(ba -> ba != BikeAccess.UNKNOWN)
      .forEach(d -> {
        var mapped = BikeAccessMapper.map(d);
        assertEquals(d.toString(), mapped.toString());
      });
  }
}
