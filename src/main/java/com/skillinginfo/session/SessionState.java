package com.skillinginfo.session;

/**
 * See SPEC.md §7 (v2) for the full state diagram, including the CANDIDATE
 * timeout and SUPPRESSED cooldown exits that were missing from the original
 * design.
 */
public enum SessionState
{
	IDLE,
	CANDIDATE,
	PROMPTED,
	ACTIVE,
	PAUSED,
	SUPPRESSED,
	COMPLETE
}
