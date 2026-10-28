package com.bidbuygo.listing;

/** Why a listing stopped being ACTIVE (null while active, and for REJECTED listings). */
public enum CloseReason {
    SOLD,
    RESERVE_NOT_MET,
    NO_BIDS
}
