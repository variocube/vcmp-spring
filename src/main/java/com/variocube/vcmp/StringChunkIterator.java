package com.variocube.vcmp;

import lombok.RequiredArgsConstructor;
import lombok.val;

import java.util.Iterator;

@RequiredArgsConstructor
public class StringChunkIterator implements Iterator<String> {
    private final String text;
    private final int chunkSize;

    private int start = 0;

    @Override
    public boolean hasNext() {
        return start < text.length();
    }

    @Override
    public String next() {
        val chunk = text.substring(start, Math.min(text.length(), start + chunkSize));
        start += chunkSize;
        return chunk;
    }
}
