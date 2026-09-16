/*
 * Copyright 2014-2019 Netflix, Inc.
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
package com.netflix.spectator.impl;

import java.io.Serializable;
import java.util.Optional;

/**
 * Utility class for quickly checking if a string contains only characters contained within
 * the given set. The set is limited to basic ascii with ranges, if you need more advanced
 * patterns then regular expressions are a better option though it will likely come with a
 * steep performance penalty.
 *
 * <p>The 128 ascii characters are stored as a pair of 64-bit words rather than a
 * {@code boolean[]}, so membership tests and set operations are a few ALU ops instead of an
 * array walk.</p>
 *
 * <p><b>This class is an internal implementation detail only intended for use within spectator.
 * It is subject to change without notice.</b></p>
 */
public final class AsciiSet implements Serializable {

  // Bumped from 1: the array field was replaced by two longs, and serialization matches by
  // field name, so an old stream would otherwise deserialize into a silently empty set.
  private static final long serialVersionUID = 2L;

  /** Throws if {@code c} is outside of ascii; used while building a set from a pattern. */
  private static void checkAscii(char c) {
    if (c >= 128) {
      throw new IllegalArgumentException("invalid pattern, '" + c + "' is not ascii");
    }
  }

  /**
   * Create a set containing ascii characters using a simple pattern. The pattern is similar
   * to a character set in regex. For example, {@code ABC} would contain the characters
   * {@code A}, {@code B}, and {@code C}. Ranges are supported so all uppercase letters could
   * be specified as {@code A-Z}. The dash, {@code -}, will be included as part of the set if
   * it is at the start or end of the pattern.
   *
   * @param pattern
   *     String specification of the character set.
   * @return
   *     Set containing the characters specified in {@code pattern}.
   */
  public static AsciiSet fromPattern(String pattern) {
    // Two locals, not a long[2]: an array indexed by a runtime value (c >>> 6) isn't scalar
    // replaced by the JIT, so it would allocate on every call (see AsciiSetBench.fromPattern_*).
    long b0 = 0L;
    long b1 = 0L;
    final int n = pattern.length();
    for (int i = 0; i < n; ++i) {
      final char c = pattern.charAt(i);

      final boolean isStartOrEnd = i == 0 || i == n - 1;
      if (isStartOrEnd || c != '-') {
        checkAscii(c);
        if (c < 64) b0 |= 1L << c; else b1 |= 1L << c;
      } else {
        final char s = pattern.charAt(i - 1);
        final char e = pattern.charAt(i + 1);
        for (char v = s; v <= e; ++v) {
          checkAscii(v);
          if (v < 64) b0 |= 1L << v; else b1 |= 1L << v;
        }
      }
    }
    return new AsciiSet(b0, b1);
  }

  /** Returns a set that matches no characters. */
  public static AsciiSet none() {
    return new AsciiSet(0L, 0L);
  }

  /** Returns a set that matches all ascii characters. */
  public static AsciiSet all() {
    // -1L has every bit set, so both words match every character.
    return new AsciiSet(-1L, -1L);
  }

  /** Returns a set that matches ascii control characters. */
  public static AsciiSet control() {
    long b0 = 0L;
    long b1 = 0L;
    for (char c = 0; c < 128; ++c) {
      if (Character.isISOControl(c)) {
        if (c < 64) b0 |= 1L << c; else b1 |= 1L << c;
      }
    }
    return new AsciiSet(b0, b1);
  }

  /**
   * Returns 1 if {@code c} is a member of the set described by the pair of words and 0 if it
   * is not. Characters outside of ascii are never members.
   *
   * <p>Takes the words as arguments rather than reading the fields so that callers scanning a
   * string can hoist them out of the loop. Returns the raw bit rather than a boolean: measured
   * faster in {@code containsAll} (a boolean-returning version cost 15-35% throughput there)
   * even though this is a small, fully inlined method where that shouldn't matter in theory.</p>
   */
  private static long memberBit(long b0, long b1, char c) {
    final long word = (c < 64) ? b0 : b1;
    // Relies on JLS 15.19: a long shift masks its distance to the low 6 bits, so `c` correctly
    // selects the bit within whichever word was chosen even though c can be up to 127. Do not
    // make that masking explicit (`c & 0x3F`): x86's shrx already masks the count in hardware,
    // so the explicit version compiles to a genuinely redundant andl per character and measured
    // 15-35% slower in containsAll (confirmed with -XX:+PrintAssembly; verify there if changing).
    return (c < 128) ? ((word >>> c) & 1L) : 0L;
  }

  /**
   * Converts the members to a pattern string. Used to provide a user friendly toString
   * implementation for the set.
   */
  private String toPattern() {
    StringBuilder buf = new StringBuilder();
    if (contains('-')) {
      buf.append('-');
    }
    boolean previous = false;
    char s = 0;
    for (int i = 0; i < 128; ++i) {
      final boolean member = contains((char) i);
      if (member && !previous) {
        s = (char) i;
      } else if (!member && previous) {
        final char e = (char) (i - 1);
        append(buf, s, e);
      }
      previous = member;
    }
    if (previous) {
      append(buf, s, (char) 127);
    }
    return buf.toString();
  }

  private static void append(StringBuilder buf, char s, char e) {
    switch (e - s) {
      case 0:  if (s != '-') buf.append(s);         break;
      case 1:  buf.append(s).append(e);             break;
      default: buf.append(s).append('-').append(e); break;
    }
  }

  /** Membership bits for characters 0-63. */
  private final long bits0;

  /** Membership bits for characters 64-127. */
  private final long bits1;

  /**
   * Cached, lazily computed pattern for {@link #toString()}; excluded from equals/hashCode and
   * from serialization since it's fully derived from {@link #bits0}/{@link #bits1}.
   */
  private transient String pattern;

  private AsciiSet(long bits0, long bits1) {
    this.bits0 = bits0;
    this.bits1 = bits1;
  }

  /**
   * Returns true if the character contained within the set. This is a constant time
   * operation.
   */
  public boolean contains(char c) {
    return memberBit(bits0, bits1, c) != 0L;
  }

  /**
   * Returns true if all characters in the string are contained within the set.
   */
  public boolean containsAll(CharSequence str) {
    // Testing and exiting per character measured faster than batching several characters
    // before branching: the branch is well predicted on real input, so batching only adds work.
    final long b0 = bits0;
    final long b1 = bits1;
    final int n = str.length();
    for (int i = 0; i < n; ++i) {
      if (memberBit(b0, b1, str.charAt(i)) == 0L) {
        return false;
      }
    }
    return true;
  }

  private int indexOfNonMember(CharSequence str) {
    final long b0 = bits0;
    final long b1 = bits1;
    final int n = str.length();
    for (int i = 0; i < n; ++i) {
      if (memberBit(b0, b1, str.charAt(i)) == 0L) {
        return i;
      }
    }
    return n;
  }

  /**
   * Replace all characters in the input string with the replacement character.
   */
  public String replaceNonMembers(String input, char replacement) {
    if (!contains(replacement)) {
      throw new IllegalArgumentException(replacement + " is not a member of " + toString());
    }
    int i = indexOfNonMember(input);
    return i < input.length() ? replaceNonMembersImpl(input, i, replacement) : input;
  }

  /**
   * Returns a new set that will match characters either in the this set or in the
   * set that is provided.
   */
  public AsciiSet union(AsciiSet set) {
    return new AsciiSet(bits0 | set.bits0, bits1 | set.bits1);
  }

  /**
   * Returns a new set that will match characters iff they are included this set and in the
   * set that is provided.
   */
  public AsciiSet intersection(AsciiSet set) {
    return new AsciiSet(bits0 & set.bits0, bits1 & set.bits1);
  }

  /**
   * Returns a new set that will match characters iff they are included this set and not in the
   * set that is provided.
   */
  public AsciiSet diff(AsciiSet set) {
    return new AsciiSet(bits0 & ~set.bits0, bits1 & ~set.bits1);
  }

  /**
   * Returns a new set that will match characters that are not included this set.
   */
  public AsciiSet invert() {
    // Every bit of both words maps to a real ascii character, so there are no padding bits
    // that need to be masked back off after the complement.
    return new AsciiSet(~bits0, ~bits1);
  }

  private String replaceNonMembersImpl(String input, int start, char replacement) {
    final long b0 = bits0;
    final long b1 = bits1;

    // A byte[] decoded as ISO-8859-1 would allocate a third less (everything here is ascii) but
    // measured slower: toCharArray/new String(char[]) are bulk-copy intrinsics that beat filling
    // a byte[] one character at a time.
    final char[] buf = input.toCharArray();
    final int n = buf.length;
    buf[start] = replacement;
    for (int i = start + 1; i < n; ++i) {
      // Reads from input, not buf: reading the array being written creates a store-to-load
      // dependency that measured slower despite the extra indirection through the string.
      if (memberBit(b0, b1, input.charAt(i)) == 0L) {
        buf[i] = replacement;
      }
    }
    return new String(buf);
  }

  /**
   * If this set matches a single character, then return an optional with that character.
   * Otherwise return an empty optional.
   */
  public Optional<Character> character() {
    if (bits1 == 0L && Long.bitCount(bits0) == 1) {
      return Optional.of((char) Long.numberOfTrailingZeros(bits0));
    }
    if (bits0 == 0L && Long.bitCount(bits1) == 1) {
      return Optional.of((char) (64 + Long.numberOfTrailingZeros(bits1)));
    }
    return Optional.empty();
  }

  /** Returns true if this set is isEmpty. */
  public boolean isEmpty() {
    return (bits0 | bits1) == 0L;
  }

  @Override public String toString() {
    String p = pattern;
    if (p == null) {
      // Benign race: pattern is a pure function of the bits, so a concurrent caller recomputes
      // the same value rather than seeing a torn or stale one.
      p = toPattern();
      pattern = p;
    }
    return p;
  }

  @Override public int hashCode() {
    return 31 * Long.hashCode(bits0) + Long.hashCode(bits1);
  }

  @Override public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof AsciiSet)) return false;
    AsciiSet other = (AsciiSet) obj;
    return bits0 == other.bits0 && bits1 == other.bits1;
  }
}
