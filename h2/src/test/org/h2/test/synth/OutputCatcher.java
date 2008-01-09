/*
 * Copyright 2004-2007 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.synth;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;

import org.h2.util.IOUtils;

/**
 * Catches the output of another process.
 */
class OutputCatcher extends Thread {
    private InputStream in;
    private LinkedList list = new LinkedList();

    OutputCatcher(InputStream in) {
        this.in = in;
    }

    String readLine(long wait) {
        long start = System.currentTimeMillis();
        while (true) {
            synchronized (list) {
                if (list.size() > 0) {
                    return (String) list.removeFirst();
                }
                try {
                    list.wait(wait);
                } catch (InterruptedException e) {
                }
                long time = System.currentTimeMillis() - start;
                if (time >= wait) {
                    return null;
                }
            }
        }
    }

    public void run() {
        StringBuffer buff = new StringBuffer();
        while (true) {
            try {
                int x = in.read();
                if (x < 0) {
                    break;
                }
                if (x < ' ') {
                    if (buff.length() > 0) {
                        String s = buff.toString();
                        buff.setLength(0);
                        synchronized (list) {
                            list.add(s);
                            list.notifyAll();
                        }
                    }
                } else {
                    buff.append((char) x);
                }
            } catch (IOException e) {
                // ignore
            }
        }
        IOUtils.closeSilently(in);
    }
}
