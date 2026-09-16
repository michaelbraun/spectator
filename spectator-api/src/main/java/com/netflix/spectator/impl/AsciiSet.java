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
 * <p>The 128 ascii characters are stored as a pair of 64-bit words held directly in the object
 * rather than as a {@code boolean[]}. A membership test is then a shift and a mask instead of a
 * pointer dereference plus an array load, and the set operations, equality, and emptiness checks
 * become a couple of instructions rather than a loop over 128 elements.</p>
 *
 * <p><b>This class is an internal implementation detail only intended for use within spectator.
 * It is subject to change without notice.</b></p>
 */
public final class AsciiSet implements Serializable {

  // Bumped when the members array was replaced by the word pair. Java serialization matches
  // fields by name, so leaving this at 1 would let a stream written by an older version
  // deserialize into a silently empty set instead of failing.
  private static final long serialVersionUID = 2L;

  /** Sets the bit for {@code c} in a two element word array, validating that it is ascii. */
  private static void set(long[] words, char c) {
    if (c >= 128) {
      throw new IllegalArgumentException("invalid pattern, '" + c + "' is not ascii");
    }
    // The shift distance is masked to the low 6 bits, so it indexes within the selected word.
    words[c >>> 6] |= 1L << c;
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
    final long[] words = new long[2];
    final int n = pattern.length();
    for (int i = 0; i < n; ++i) {
      final char c = pattern.charAt(i);

      final boolean isStartOrEnd = i == 0 || i == n - 1;
      if (isStartOrEnd || c != '-') {
        set(words, c);
      } else {
        final char s = pattern.charAt(i - 1);
        final char e = pattern.charAt(i + 1);
        for (char v = s; v <= e; ++v) {
          set(words, v);
        }
      }
    }
    return new AsciiSet(words[0], words[1]);
  }

  /** Returns a set that matches no characters. */
  public static AsciiSet none() {
    return new AsciiSet(0L, 0L);
  }

  /** Returns a set that matches all ascii characters. */
  public static AsciiSet all() {
    return new AsciiSet(-1L, -1L);
  }

  /** Returns a set that matches ascii control characters. */
  public static AsciiSet control() {
    final long[] words = new long[2];
    for (char c = 0; c < 128; ++c) {
      if (Character.isISOControl(c)) {
        set(words, c);
      }
    }
    return new AsciiSet(words[0], words[1]);
  }

  /**
   * Returns 1 if {@code c} is a member of the set described by the pair of words and 0 if it
   * is not. Characters outside of ascii are never members.
   *
   * <p>Takes the words as arguments rather than reading the fields so that callers scanning a
   * string can hoist them out of the loop.</p>
   */
  private static long memberBit(long b0, long b1, char c) {
    // Both selects compile to conditional moves, so neither adds a branch to mispredict.
    final long word = (c < 64) ? b0 : b1;
    // The shift distance is masked to the low 6 bits, which is the index within the word.
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
   * Cached pattern for {@link #toString()}. Computed on demand as the parser builds a large
   * number of intermediate sets, via {@link #union(AsciiSet)} and friends, whose pattern is
   * never rendered. Not part of the identity of the set, so it is left out of equals and
   * hashCode and is recomputed after deserialization.
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
    // The words are hoisted so the loop does not reload them through the object on every
    // character. Testing and exiting per character beats accumulating a batch of characters
    // before branching: on the input this sees the branch is almost perfectly predicted, so
    // batching only adds work and lengthens the dependency chain.
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

    // Everything written here is ascii, so the result could be built as a byte[] and handed to
    // the ISO-8859-1 String constructor, which would allocate a third less. It measures slower:
    // toCharArray is an intrinsic bulk copy and the compress back down in new String(char[]) is
    // one too, and together they beat filling a byte[] a character at a time.
    final char[] buf = input.toCharArray();
    final int n = buf.length;
    buf[start] = replacement;
    for (int i = start + 1; i < n; ++i) {
      // Read the character from the input rather than back out of buf. Loading from the same
      // array that is being stored into makes each load depend on the preceding store, and it
      // measures slower than going through the string despite the extra indirection.
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
      // Benign race: the pattern is derived purely from the immutable bits, so concurrent
      // callers either see the cached value or recompute an identical one.
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
