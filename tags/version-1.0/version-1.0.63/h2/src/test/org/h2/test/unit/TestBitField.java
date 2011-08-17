/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html). 
 * Initial Developer: H2 Group 
 */
package org.h2.test.unit;

import java.util.BitSet;
import java.util.Random;

import org.h2.test.TestBase;
import org.h2.util.BitField;

/**
 * @author Thomas
 */

public class TestBitField extends TestBase {

    public void test() throws Exception {
        testRandom();
        testGetSet();
    }

    void testRandom() throws Exception {
        BitField bits = new BitField();
        BitSet set = new BitSet();
        int max = 300;
        int count = 100000;
        Random random = new Random(1);
        for (int i = 0; i < count; i++) {
            int idx = random.nextInt(max);
            if (random.nextBoolean()) {
                if (random.nextBoolean()) {
                    bits.set(idx);
                    set.set(idx);
                } else {
                    bits.clear(idx);
                    set.clear(idx);
                }
            } else {
                check(bits.get(idx), set.get(idx));
                check(bits.nextClearBit(idx), set.nextClearBit(idx));
                check(bits.nextSetBit(idx), set.nextSetBit(idx));
            }
        }
    }

    void testGetSet() throws Exception {
        BitField bits = new BitField();
        for (int i = 0; i < 10000; i++) {
            bits.set(i);
            if (!bits.get(i)) {
                throw new Exception("not set: " + i);
            }
            if (bits.get(i + 1)) {
                throw new Exception("set: " + i);
            }
        }
        for (int i = 0; i < 10000; i++) {
            if (!bits.get(i)) {
                throw new Exception("not set: " + i);
            }
        }
        for (int i = 0; i < 1000; i++) {
            int k = bits.nextClearBit(0);
            if (k != 10000) {
                throw new Exception("" + k);
            }
        }
    }
}