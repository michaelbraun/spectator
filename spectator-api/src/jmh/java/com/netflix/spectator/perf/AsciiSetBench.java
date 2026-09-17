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
package com.netflix.spectator.perf;

import com.netflix.spectator.impl.AsciiSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Optional;

/**
 * Measures {@link AsciiSet}'s current behavior on the inputs that matter in practice.
 * {@code containsAll} is called once per tag name/key/value on every meter lookup in
 * {@code AtlasRegistry.normalizeTags}, almost always on strings that are already valid, so the
 * clean cases dominate; {@code replaceNonMembers} covers the allocating path taken when a tag
 * really is invalid; {@code union}/{@code equals}/{@code character}/{@code isEmpty} cover what
 * the pattern parser does while compiling a matcher.
 *
 * <p>To A/B a change to this class, build two jmh jars from two git states of
 * {@code AsciiSet.java} and run this benchmark against each, rather than reproducing an old
 * implementation inside this file: which class a method is compiled as part of can change its
 * measured throughput by more than an algorithm difference the comparison is meant to isolate
 * (see the commit that introduced this note for a case where that produced a false ~13%
 * regression in {@code AtlasRegistry.normalizeTags}).</p>
 */
@State(Scope.Thread)
public class AsciiSetBench {

  /** The character set the Atlas registry applies to every tag key and value. */
  private static final String VALID = "-._A-Za-z0-9~^";

  private final AsciiSet set = AsciiSet.fromPattern(VALID);
  private final AsciiSet other = AsciiSet.fromPattern("a-z");
  private final AsciiSet setCopy = AsciiSet.fromPattern(VALID);

  // The inputs are built in setup rather than assigned from literals inline: a final field
  // holding a literal can be constant folded into the benchmark body, which lets the JIT
  // evaluate the whole scan at compile time and reports a speed the real call never sees.

  /** Typical tag value: short and already valid. */
  private String shortClean;

  /** Typical metric name: longer, still valid. */
  private String longClean;

  /** A value that needs fixing, with the first bad character near the end. */
  private String dirtyLate;

  /** A value that needs fixing from the very first character. */
  private String dirtyEarly;

  /** Text to build a set from, kept separate from {@code VALID} for the same reason as above. */
  private String patternText;

  @Setup
  public void setup() {
    shortClean = new String("us-east-1c".toCharArray());
    longClean = new String("ipc.server.call.duration.percentile".toCharArray());
    dirtyLate = new String("spinnaker.prod.us-east-1/cluster".toCharArray());
    dirtyEarly = new String("/api/v1/users?id=42&name=bob".toCharArray());
    patternText = new String(VALID.toCharArray());
  }

  @Benchmark public AsciiSet fromPattern() {
    return AsciiSet.fromPattern(patternText);
  }

  // containsAll on already valid input: the dominant case on the meter lookup path.

  @Benchmark public boolean shortClean() {
    return set.containsAll(shortClean);
  }

  @Benchmark public boolean longClean() {
    return set.containsAll(longClean);
  }

  // containsAll that fails, so the scan exits early.

  @Benchmark public boolean dirty() {
    return set.containsAll(dirtyLate);
  }

  // replaceNonMembers: the allocating path.

  @Benchmark public String replaceClean() {
    return set.replaceNonMembers(longClean, '_');
  }

  @Benchmark public String replaceLate() {
    return set.replaceNonMembers(dirtyLate, '_');
  }

  @Benchmark public String replaceEarly() {
    return set.replaceNonMembers(dirtyEarly, '_');
  }

  // Single character membership, as used by the pattern matcher.

  @Benchmark public void contains(Blackhole bh) {
    bh.consume(set.contains('a'));
    bh.consume(set.contains('/'));
    bh.consume(set.contains('é'));
  }

  // Set algebra and identity, as used while compiling a pattern.

  @Benchmark public AsciiSet union() {
    return set.union(other);
  }

  @Benchmark public boolean equalsSet() {
    return set.equals(setCopy);
  }

  @Benchmark public boolean isEmpty() {
    return set.isEmpty();
  }

  @Benchmark public Optional<Character> character() {
    return set.character();
  }
}
