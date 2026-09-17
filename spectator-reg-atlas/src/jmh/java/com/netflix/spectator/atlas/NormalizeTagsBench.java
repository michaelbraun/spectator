/*
 * Copyright 2014-2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spectator.atlas;

import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.Id;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * {@code AtlasRegistry.normalizeTags} runs on every lookup of a {@code DefaultId} (it is not
 * cached the way a foreign {@code Id} implementation is, see {@code AbstractRegistry.normalizeId}),
 * so a meter that is looked up repeatedly with a persistently invalid tag value pays this cost
 * every time, not just once.
 *
 * <p>Compare this A/B by building two jmh jars from two git states of {@code AtlasRegistry.java}
 * and running the same benchmark against each, rather than reproducing the old algorithm on this
 * class: which class a method compiles as part of can shift its measured throughput more than the
 * algorithm difference being isolated.</p>
 */
@State(Scope.Thread)
public class NormalizeTagsBench {

  private final AtlasRegistry registry = new AtlasRegistry(Clock.SYSTEM, System::getProperty);

  /** All tags valid: the dominant real-world case. */
  private final Id clean = registry.createId("test")
      .withTag("a", "1").withTag("b", "2").withTag("c", "3").withTag("d", "4").withTag("e", "5");

  /** Every tag but the last is confirmed clean before the detection scan finds the dirty one. */
  private final Id dirtyLastTag = registry.createId("test")
      .withTag("a", "1").withTag("b", "2").withTag("c", "3").withTag("d", "4")
      .withTag("path", "/api/v1");

  /**
   * The dirty tag is neither first nor last: earlier tags are confirmed clean and reused, but
   * later ones were never checked by the detection scan (it breaks at the first dirty tag), so
   * they still get rescanned by replaceNonMembers below even though they turn out to be clean.
   */
  private final Id dirtyMiddleTag = registry.createId("test")
      .withTag("a", "1").withTag("b", "2").withTag("path", "/api/v1")
      .withTag("d", "4").withTag("e", "5");

  /** The name itself is dirty: no tag detection scan runs either way. */
  private final Id dirtyName = registry.createId("te/st")
      .withTag("a", "1").withTag("b", "2").withTag("c", "3").withTag("d", "4").withTag("e", "5");

  @Benchmark public Id clean() {
    return registry.normalizeTags(clean);
  }

  @Benchmark public Id dirtyLastTag() {
    return registry.normalizeTags(dirtyLastTag);
  }

  @Benchmark public Id dirtyMiddleTag() {
    return registry.normalizeTags(dirtyMiddleTag);
  }

  @Benchmark public Id dirtyName() {
    return registry.normalizeTags(dirtyName);
  }
}
