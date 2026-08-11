package com.skillinginfo.session;

/**
 * Live rate figures read from RuneLite's own {@code XpTrackerService}
 * rather than recomputed here (SPEC.md §5: don't recreate proven
 * mechanisms). Sampled on the client thread each tick and read from the
 * Swing EDT, hence the volatile fields - the same discipline that the
 * {@code ItemManager} thread-safety crash taught.
 * <p>
 * Two things this deliberately does NOT cover, because it can't:
 * <ul>
 * <li>Completed sessions in history. XpTrackerService only knows about
 * right now, so history keeps deriving its rates from each session's own
 * persisted raw XP and active time (§34) - those numbers have to stay
 * reproducible from the record long after the client restarted.</li>
 * <li>The session's XP total itself, which stays ours: it's the raw fact
 * the whole record is built on, and it must align with our approved
 * start/stop boundaries rather than XP Tracker's independent session.</li>
 * </ul>
 * The rate figures shown live are therefore XP Tracker's, and will reflect
 * a user resetting XP Tracker mid-session.
 */
public class LiveRates
{
	private volatile int xpPerHour;
	private volatile int actions;
	private volatile int actionsPerHour;

	public void update(int xpPerHour, int actions, int actionsPerHour)
	{
		this.xpPerHour = xpPerHour;
		this.actions = actions;
		this.actionsPerHour = actionsPerHour;
	}

	public void clear()
	{
		update(0, 0, 0);
	}

	public int getXpPerHour()
	{
		return xpPerHour;
	}

	public int getActions()
	{
		return actions;
	}

	public int getActionsPerHour()
	{
		return actionsPerHour;
	}
}
