package com.skillinginfo.session;

/**
 * SPEC.md §20b. Whether an item-flow entry could be tied to a specific
 * generating event (PER_DROP) or only to the session as a whole
 * (SESSION_AGGREGATE) - see §20b for when each applies.
 */
public enum AttributionConfidence
{
	PER_DROP,
	SESSION_AGGREGATE
}
