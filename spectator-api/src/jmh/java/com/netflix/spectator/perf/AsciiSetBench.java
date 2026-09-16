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

import java.util.Arrays;
import java.util.Optional;

/**
 * A/B comparison of {@link AsciiSet} against the previous {@code boolean[128]} implementation,
 * reproduced here as {@link ArraySet} so both run in the same JVM and see the same JIT state.
 *
 * <p>The inputs mirror the way the class is actually used. {@code AtlasRegistry.normalizeTags}
 * calls {@code containsAll} once for the name and twice per tag on every meter lookup, almost
 * always on strings that are already valid, so the clean {@code containsAll} cases dominate.
 * The {@code replace} cases cover the allocation path taken when a tag really does contain an
 * invalid character, and {@code union}/{@code equals} cover what the pattern parser does while
 * compiling a matcher.</p>
 */
@State(Scope.Thread)
public class AsciiSetBench {

  /** The implementation as it stood before the word pair change. */
  private static final class ArraySet {

    private final boolean[] members;

    private ArraySet(boolean[] members) {
      this.members = members;
    }

    static ArraySet fromPattern(String pattern) {
      final boolean[] members = new boolean[128];
      final int n = pattern.length();
      for (int i = 0; i < n; ++i) {
        final char c = pattern.charAt(i);
        if (c >= members.length) {
          throw new IllegalArgumentException("invalid pattern, '" + c + "' is not ascii");
        }
        final boolean isStartOrEnd = i == 0 || i == n - 1;
        if (isStartOrEnd || c != '-') {
          members[c] = true;
        } else {
          final char s = pattern.charAt(i - 1);
          final char e = pattern.charAt(i + 1);
          for (char v = s; v <= e; ++v) {
            members[v] = true;
          }
        }
      }
      return new ArraySet(members);
    }

    boolean contains(char c) {
      return c < 128 && members[c];
    }

    boolean containsAll(CharSequence str) {
      final int n = str.length();
      for (int i = 0; i < n; ++i) {
        if (!contains(str.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    private int indexOfNonMember(CharSequence str) {
      final int n = str.length();
      for (int i = 0; i < n; ++i) {
        if (!contains(str.charAt(i))) {
          return i;
        }
      }
      return n;
    }

    String replaceNonMembers(String input, char replacement) {
      int i = indexOfNonMember(input);
      return i < input.length() ? replaceNonMembersImpl(input, i, replacement) : input;
    }

    private String replaceNonMembersImpl(String input, int start, char replacement) {
      final int n = input.length();
      final char[] buf = input.toCharArray();
      buf[start] = replacement;
      for (int i = start + 1; i < n; ++i) {
        final char c = input.charAt(i);
        if (!contains(c))
          buf[i] = replacement;
      }
      return new String(buf);
    }

    ArraySet union(ArraySet set) {
      final boolean[] unionMembers = new boolean[128];
      for (int i = 0; i < unionMembers.length; ++i) {
        unionMembers[i] = members[i] || set.members[i];
      }
      return new ArraySet(unionMembers);
    }

    Optional<Character> character() {
      char c = 0;
      int count = 0;
      for (int i = 0; i < members.length; ++i) {
        if (members[i]) {
          c = (char) i;
          ++count;
        }
      }
      return (count == 1) ? Optional.of(c) : Optional.empty();
    }

    boolean isEmpty() {
      for (boolean b : members) {
        if (b) {
          return false;
        }
      }
      return true;
    }

    @Override public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof ArraySet)) return false;
      return Arrays.equals(members, ((ArraySet) obj).members);
    }

    @Override public int hashCode() {
      return Arrays.hashCode(members);
    }
  }

  /** The character set the Atlas registry applies to every tag key and value. */
  private static final String VALID = "-._A-Za-z0-9~^";

  private final AsciiSet set = AsciiSet.fromPattern(VALID);
  private final ArraySet arraySet = ArraySet.fromPattern(VALID);

  private final AsciiSet other = AsciiSet.fromPattern("a-z");
  private final ArraySet arrayOther = ArraySet.fromPattern("a-z");

  private final AsciiSet setCopy = AsciiSet.fromPattern(VALID);
  private final ArraySet arraySetCopy = ArraySet.fromPattern(VALID);

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

  /**
   * Sets the bit for {@code c} in a two element word array, the way {@code AsciiSet.fromPattern}
   * used to before it switched to two locals. The array is indexed by {@code c >>> 6}, a value
   * only known at runtime, and that keeps C2 from scalar replacing it: the array is a real,
   * short-lived heap allocation on every call, for no benefit over just using two locals.
   */
  private static void setArrayWord(long[] words, char c) {
    words[c >>> 6] |= 1L << c;
  }

  private static long[] fromPatternArray(String pattern) {
    final long[] words = new long[2];
    final int n = pattern.length();
    for (int i = 0; i < n; ++i) {
      final char c = pattern.charAt(i);
      final boolean isStartOrEnd = i == 0 || i == n - 1;
      if (isStartOrEnd || c != '-') {
        setArrayWord(words, c);
      } else {
        final char s = pattern.charAt(i - 1);
        final char e = pattern.charAt(i + 1);
        for (char v = s; v <= e; ++v) {
          setArrayWord(words, v);
        }
      }
    }
    return words;
  }

  /** Same computation as {@link #fromPatternArray}, but through two locals instead of an array. */
  private static long fromPatternScalar(String pattern) {
    long b0 = 0L;
    long b1 = 0L;
    final int n = pattern.length();
    for (int i = 0; i < n; ++i) {
      final char c = pattern.charAt(i);
      final boolean isStartOrEnd = i == 0 || i == n - 1;
      if (isStartOrEnd || c != '-') {
        if (c < 64) b0 |= 1L << c; else b1 |= 1L << c;
      } else {
        final char s = pattern.charAt(i - 1);
        final char e = pattern.charAt(i + 1);
        for (char v = s; v <= e; ++v) {
          if (v < 64) b0 |= 1L << v; else b1 |= 1L << v;
        }
      }
    }
    return b0 ^ b1;
  }

  // Construction cost only, with no final AsciiSet built on either side: the long[2] above,
  // indexed by a value known only at runtime, versus the two locals AsciiSet.fromPattern
  // actually uses. Building the AsciiSet itself would allocate the same object either way and
  // wash out the difference this is meant to isolate.

  @Benchmark public long fromPattern_array() {
    final long[] words = fromPatternArray(patternText);
    return words[0] ^ words[1];
  }

  @Benchmark public long fromPattern_scalar() {
    return fromPatternScalar(patternText);
  }

  // containsAll on already valid input: the dominant case on the meter lookup path.

  @Benchmark public boolean shortClean_new() {
    return set.containsAll(shortClean);
  }

  @Benchmark public boolean shortClean_old() {
    return arraySet.containsAll(shortClean);
  }

  @Benchmark public boolean longClean_new() {
    return set.containsAll(longClean);
  }

  @Benchmark public boolean longClean_old() {
    return arraySet.containsAll(longClean);
  }

  // containsAll that fails, so the scan exits early.

  @Benchmark public boolean dirty_new() {
    return set.containsAll(dirtyLate);
  }

  @Benchmark public boolean dirty_old() {
    return arraySet.containsAll(dirtyLate);
  }

  // replaceNonMembers: the allocating path.

  @Benchmark public String replaceClean_new() {
    return set.replaceNonMembers(longClean, '_');
  }

  @Benchmark public String replaceClean_old() {
    return arraySet.replaceNonMembers(longClean, '_');
  }

  @Benchmark public String replaceLate_new() {
    return set.replaceNonMembers(dirtyLate, '_');
  }

  @Benchmark public String replaceLate_old() {
    return arraySet.replaceNonMembers(dirtyLate, '_');
  }

  @Benchmark public String replaceEarly_new() {
    return set.replaceNonMembers(dirtyEarly, '_');
  }

  @Benchmark public String replaceEarly_old() {
    return arraySet.replaceNonMembers(dirtyEarly, '_');
  }

  // Single character membership, as used by the pattern matcher.

  @Benchmark public void contains_new(Blackhole bh) {
    bh.consume(set.contains('a'));
    bh.consume(set.contains('/'));
    bh.consume(set.contains('\u00e9'));
  }

  @Benchmark public void contains_old(Blackhole bh) {
    bh.consume(arraySet.contains('a'));
    bh.consume(arraySet.contains('/'));
    bh.consume(arraySet.contains('\u00e9'));
  }

  // Set algebra and identity, as used while compiling a pattern.

  @Benchmark public AsciiSet union_new() {
    return set.union(other);
  }

  @Benchmark public ArraySet union_old() {
    return arraySet.union(arrayOther);
  }

  @Benchmark public boolean equals_new() {
    return set.equals(setCopy);
  }

  @Benchmark public boolean equals_old() {
    return arraySet.equals(arraySetCopy);
  }

  @Benchmark public boolean isEmpty_new() {
    return set.isEmpty();
  }

  @Benchmark public boolean isEmpty_old() {
    return arraySet.isEmpty();
  }

  @Benchmark public Optional<Character> character_new() {
    return set.character();
  }

  @Benchmark public Optional<Character> character_old() {
    return arraySet.character();
  }
}
