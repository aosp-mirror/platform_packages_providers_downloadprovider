package com.android.providers.downloads;

class RealSystemFacade implements SystemFacade {
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
