package com.careerai.backend.channel;

/** ALL includes expired publications, never manually archived or invalid records. */
public enum ChannelFreshnessScope {
    CURRENT, EXPIRED, ALL
}
