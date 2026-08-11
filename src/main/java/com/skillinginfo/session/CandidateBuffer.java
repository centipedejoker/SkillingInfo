package com.skillinginfo.session;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded buffer of candidate XP events for a single skill (SPEC.md §10).
 * Events older than the detection window are pruned every tick so a stalled
 * buffer naturally empties out rather than lingering forever.
 */
public class CandidateBuffer
{
	private final List<QualifyingXpEvent> events = new ArrayList<>();

	public void add(QualifyingXpEvent event)
	{
		events.add(event);
	}

	public void pruneOlderThan(int cutoffTick)
	{
		Iterator<QualifyingXpEvent> it = events.iterator();
		while (it.hasNext())
		{
			if (it.next().getTick() < cutoffTick)
			{
				it.remove();
			}
			else
			{
				// events are appended in tick order, so once we hit one
				// that's within the window, the rest are too
				break;
			}
		}
	}

	public boolean isEmpty()
	{
		return events.isEmpty();
	}

	public List<QualifyingXpEvent> events()
	{
		return events;
	}
}
