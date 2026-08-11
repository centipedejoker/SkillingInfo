package com.skillinginfo.session;

/**
 * Accumulates active/idle milliseconds one game tick at a time. Active and
 * idle time are simply "time spent while the session was ACTIVE" vs. "time
 * spent while the session was PAUSED" - the auto-pause decision itself
 * lives in SessionManager (SPEC.md §13). This keeps total = active + idle
 * exactly, matching the §11 mockup.
 */
public class SessionClock
{
	private static final long TICK_MS = 600;

	private long activeMs;
	private long idleMs;

	public void tickActive()
	{
		activeMs += TICK_MS;
	}

	public void tickIdle()
	{
		idleMs += TICK_MS;
	}

	public long getActiveSeconds()
	{
		return activeMs / 1000;
	}

	public long getIdleSeconds()
	{
		return idleMs / 1000;
	}

	public long getTotalSeconds()
	{
		return (activeMs + idleMs) / 1000;
	}

	public void reset()
	{
		activeMs = 0;
		idleMs = 0;
	}
}
