package com.android.providers.downloads;

public class FakeSystemFacade implements SystemFacade {
    private long mTimeMillis = 0;

    void incrementTimeMillis(long delta) {
        mTimeMillis += delta;
    }

    public long currentTimeMillis() {
        return mTimeMillis;
    }
}
