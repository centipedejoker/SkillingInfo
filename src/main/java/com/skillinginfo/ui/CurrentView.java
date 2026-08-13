package com.skillinginfo.ui;

import com.skillinginfo.session.ActivitySession;
import com.skillinginfo.session.FutureXpResolver;
import com.skillinginfo.session.ItemFlowEntry;
import com.skillinginfo.session.ItemUse;
import com.skillinginfo.session.ItemUseStore;
import com.skillinginfo.session.ProjectionBuilder;
import com.skillinginfo.session.PromptSummary;
import com.skillinginfo.session.SessionManager;
import com.skillinginfo.session.SessionState;
import com.skillinginfo.session.TrackingGroups;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The CURRENT tab: idle, detection prompt, and the running session
 * (SPEC.md §6, §11), laid out to the Phase 7 design.
 * <p>
 * The organising idea of that design is that <b>retention leads</b>. It is the
 * only figure given full contrast and a bar, and it sits above the rate table,
 * because kept items are the product while XP/hour is table stakes that other
 * plugins already show. Paired stat tiles replace the old label/value list,
 * which is where most of the vertical saving comes from - the old screen
 * pushed retention to row twelve, below the fold.
 * <p>
 * Rows are cached and only rebuilt when the set of displayed items changes.
 * refresh() runs every game tick, and rebuilding destroyed the use-selector
 * while the player was interacting with it.
 */
class CurrentView extends JPanel
{
	private static final String IDLE_CARD = "IDLE";
	private static final String PROMPT_CARD = "PROMPT";
	private static final String SESSION_CARD = "SESSION";
	private static final int ITEM_ICON = 32;
	private static final int SKILL_ICON = 20;

	private final SessionManager sessionManager;
	private final ItemUseStore itemUseStore;
	private final Map<Integer, String> itemNames;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;
	private final Runnable onAction;

	/**
	 * §48a `[v9]`: every button here fires on the EDT, and session state
	 * belongs to the client thread. Nothing in {@code session/} is
	 * synchronised and nothing should be, so mutations hop instead.
	 * <p>
	 * The bug this closes isn't a torn read. {@code onGameTick} samples
	 * {@code state} at its switch and acts on it several statements later, so
	 * a {@code start()} landing in that gap left the session ACTIVE with the
	 * state machine already past the point of noticing - recording nothing
	 * while the panel showed the idle card. It presents as "I pressed Start
	 * and nothing happened".
	 */
	private final Consumer<Runnable> onClientThread;

	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards = new JPanel(cardLayout);

	private final JLabel promptTitle = Ui.bold("", Palette.TEXT);
	private final JLabel promptDetail = Ui.dim("");
	private final JLabel promptIcon = new JLabel();
	private final Ui.Bar promptCountdown = new Ui.Bar(2);
	private final JLabel promptFootnote = Ui.label("", FontManager.getRunescapeSmallFont(), Palette.DIMMEST);

	private final JLabel headerIcon = new JLabel();
	private final JLabel skillLabel = Ui.bold("", Palette.TEXT);
	private final JLabel activityLabel = Ui.dim("");
	private final JLabel totalTimeLabel = Ui.bold("", Palette.TEXT);
	private final JLabel statusLabel = Ui.label("", FontManager.getRunescapeSmallFont(), Palette.ACCENT);

	private final JPanel pausedBand = new JPanel(new BorderLayout());
	private final JLabel pausedDetail = Ui.dim("");

	private final JPanel retentionBlock = new JPanel();
	private final JLabel retentionCaption = Ui.label("RETAINED", FontManager.getRunescapeSmallFont(), Palette.DIM);
	private final JLabel retentionValue = Ui.bold("", Palette.TEXT);
	private final Ui.Bar retentionBar = new Ui.Bar(6);
	private final JLabel keptLabel = Ui.dim("");
	private final JLabel lostLabel = Ui.dim("");

	private final JPanel outputPanel = new JPanel();
	private Set<Integer> renderedItemIds = new LinkedHashSet<>();
	private final List<ItemRow> itemRows = new ArrayList<>();

	private final JLabel activeValue = Ui.bold("", Palette.TEXT);
	private final JLabel idleValue = Ui.bold("", Palette.DIM);
	private final JLabel xpValue = Ui.bold("", Palette.TEXT);
	private final JLabel xpHrValue = Ui.bold("", Palette.TEXT);
	private final JLabel actionsValue = Ui.bold("", Palette.TEXT);
	private final JLabel actionsHrValue = Ui.bold("", Palette.TEXT);
	private final JPanel actionsRow;
	private final JLabel killsValue = Ui.bold("", Palette.TEXT);
	private final JLabel kphValue = Ui.bold("", Palette.TEXT);
	private final JPanel killsRow;
	private final JLabel dependencyHintLabel = Ui.label("", FontManager.getRunescapeSmallFont(), Palette.DIM);
	private final JPanel dependencyHintBox = new JPanel(new BorderLayout());
	private final JLabel overflowLine = Ui.label("", FontManager.getRunescapeSmallFont(), Palette.DIMMEST);

	private final JPanel projectionPanel = new JPanel();

	private final JButton pauseResumeButton = flatButton("Pause", false);
	private final JButton stopButton = flatButton("Stop", false);

	private static final class ItemRow
	{
		final int itemId;
		final JLabel name;
		final JLabel sub;
		final JLabel net;

		ItemRow(int itemId, JLabel name, JLabel sub, JLabel net)
		{
			this.itemId = itemId;
			this.name = name;
			this.sub = sub;
			this.net = net;
		}
	}

	CurrentView(SessionManager sessionManager, ItemUseStore itemUseStore, Map<Integer, String> itemNames,
		ItemManager itemManager, SkillIconManager skillIconManager, Runnable onAction,
		Consumer<Runnable> onClientThread)
	{
		this.sessionManager = sessionManager;
		this.itemUseStore = itemUseStore;
		this.itemNames = itemNames;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
		this.onAction = onAction;
		this.onClientThread = onClientThread;

		this.actionsRow = Ui.tileRow(
			Ui.tile("ACTIONS", actionsValue, false),
			Ui.tile("ACTIONS/HR", actionsHrValue, false));
		this.killsRow = Ui.tileRow(
			Ui.tile("KILLS", killsValue, false),
			Ui.tile("KPH", kphValue, false));

		setLayout(new BorderLayout());
		setBackground(Palette.PANEL);
		cards.setBackground(Palette.PANEL);

		cards.add(buildIdleCard(), IDLE_CARD);
		cards.add(buildPromptCard(), PROMPT_CARD);
		cards.add(buildSessionCard(), SESSION_CARD);
		add(cards, BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	private static JButton flatButton(String text, boolean primary)
	{
		JButton b = new JButton(text);
		b.setBackground(Palette.PANEL);
		b.setFocusPainted(false);
		b.setMargin(new java.awt.Insets(0, 0, 0, 0));
		restyleButton(b, primary);
		return b;
	}

	private static void restyleButton(JButton b, boolean primary)
	{
		b.setFont(primary ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		b.setForeground(primary ? Palette.ACCENT : Palette.TEXT);
		b.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(primary ? Palette.ACCENT : Palette.BORDER),
			BorderFactory.createEmptyBorder(4, 0, 4, 0)));
	}

	private static JLabel iconHolder(int size)
	{
		JLabel l = new JLabel();
		Dimension d = new Dimension(size, size);
		l.setPreferredSize(d);
		l.setMinimumSize(d);
		l.setMaximumSize(d);
		l.setOpaque(true);
		l.setBackground(Palette.WELL);
		l.setBorder(BorderFactory.createLineBorder(Palette.BORDER));
		return l;
	}

	private static JPanel alignRight(Component c)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(c, BorderLayout.EAST);
		return p;
	}

	private JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(Palette.PANEL);
		return p;
	}

	/** Keeps content pinned to the top instead of centring in leftover space. */
	private JPanel wrapTop(JPanel content)
	{
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(Palette.PANEL);
		wrapper.add(content, BorderLayout.NORTH);
		return wrapper;
	}

	private JPanel plainHeader()
	{
		JPanel head = new JPanel(new BorderLayout());
		head.setBackground(Palette.PANEL);
		head.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
		head.add(Ui.bold("Skilling Info", Palette.TEXT), BorderLayout.WEST);
		head.add(alignRight(Ui.dim("watching")), BorderLayout.EAST);
		return Ui.fixHeight(head);
	}

	private JPanel buildIdleCard()
	{
		JPanel p = column();
		p.add(plainHeader());
		p.add(Ui.rule(Palette.BORDER));

		JLabel copy = Ui.label(
			"<html><body style='width:195px'>Nothing worth tracking yet.<br><br>"
				+ "Keep playing. When the same activity repeats long enough to measure, "
				+ "you'll get one offer to start a session.</body></html>",
			FontManager.getRunescapeSmallFont(), Palette.DIM);
		copy.setBorder(BorderFactory.createEmptyBorder(12, 2, 12, 2));
		p.add(Ui.fixHeight(copy));

		return wrapTop(p);
	}

	private JPanel buildPromptCard()
	{
		JPanel p = column();
		p.add(plainHeader());
		p.add(Ui.rule(Palette.BORDER));
		p.add(Ui.gap(8));

		// The plugin's only interruption, so it is deliberately a quiet
		// offer: it sits in the panel's normal flow with no colour fill and
		// no badge. The only accent is the Start button's outline.
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(Palette.TILE);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Palette.BORDER),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JPanel titleRow = new JPanel(new BorderLayout(6, 0));
		titleRow.setOpaque(false);
		promptIcon.setPreferredSize(new Dimension(SKILL_ICON, SKILL_ICON));
		titleRow.add(promptIcon, BorderLayout.WEST);
		titleRow.add(promptTitle, BorderLayout.CENTER);
		box.add(Ui.fixHeight(titleRow));

		promptDetail.setBorder(BorderFactory.createEmptyBorder(4, 26, 7, 0));
		box.add(Ui.fixHeight(promptDetail));

		JButton start = flatButton("Start", true);
		JButton ignore = flatButton("Ignore", false);
		start.addActionListener(e -> act(sessionManager::start));
		ignore.addActionListener(e -> act(sessionManager::ignore));
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		buttons.add(start);
		buttons.add(ignore);
		box.add(Ui.fixHeight(buttons));

		box.add(Ui.gap(6));
		box.add(promptCountdown);
		p.add(Ui.fixHeight(box));

		promptFootnote.setBorder(BorderFactory.createEmptyBorder(10, 2, 0, 2));
		p.add(Ui.fixHeight(promptFootnote));

		return wrapTop(p);
	}

	private JPanel buildSessionCard()
	{
		JPanel p = column();

		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setBackground(Palette.PANEL);
		head.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

		headerIcon.setPreferredSize(new Dimension(SKILL_ICON, SKILL_ICON));
		head.add(headerIcon, BorderLayout.WEST);

		JPanel identity = new JPanel();
		identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
		identity.setOpaque(false);
		identity.add(skillLabel);
		identity.add(activityLabel);
		head.add(identity, BorderLayout.CENTER);

		JPanel clock = new JPanel();
		clock.setLayout(new BoxLayout(clock, BoxLayout.Y_AXIS));
		clock.setOpaque(false);
		totalTimeLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
		statusLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
		clock.add(totalTimeLabel);
		clock.add(statusLabel);
		head.add(clock, BorderLayout.EAST);

		p.add(Ui.fixHeight(head));
		p.add(Ui.rule(Palette.BORDER));

		pausedBand.setBackground(Palette.WELL);
		pausedBand.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, Palette.ACCENT),
			BorderFactory.createEmptyBorder(5, 6, 5, 6)));
		pausedBand.add(Ui.bold("PAUSED", Palette.ACCENT), BorderLayout.WEST);
		pausedBand.add(alignRight(pausedDetail), BorderLayout.EAST);
		pausedBand.setVisible(false);
		p.add(Ui.gap(6));
		p.add(Ui.fixHeight(pausedBand));

		retentionBlock.setLayout(new BoxLayout(retentionBlock, BoxLayout.Y_AXIS));
		retentionBlock.setBackground(Palette.TILE);
		retentionBlock.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Palette.BORDER),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));

		JPanel retentionTop = new JPanel(new BorderLayout());
		retentionTop.setOpaque(false);
		retentionTop.add(retentionCaption, BorderLayout.WEST);
		retentionTop.add(alignRight(retentionValue), BorderLayout.EAST);
		retentionBlock.add(Ui.fixHeight(retentionTop));
		retentionBlock.add(Ui.gap(5));
		retentionBlock.add(retentionBar);
		retentionBlock.add(Ui.gap(4));

		JPanel keptLost = new JPanel(new BorderLayout());
		keptLost.setOpaque(false);
		keptLost.add(keptLabel, BorderLayout.WEST);
		keptLost.add(alignRight(lostLabel), BorderLayout.EAST);
		retentionBlock.add(Ui.fixHeight(keptLost));

		p.add(Ui.gap(6));
		p.add(Ui.fixHeight(retentionBlock));

		p.add(Ui.gap(6));
		p.add(Ui.band("OUTPUT"));
		outputPanel.setLayout(new BoxLayout(outputPanel, BoxLayout.Y_AXIS));
		outputPanel.setBackground(Palette.PANEL);
		outputPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(outputPanel);

		p.add(Ui.gap(6));
		p.add(Ui.band("SESSION"));
		p.add(Ui.gap(3));
		p.add(Ui.tileRow(Ui.tile("ACTIVE", activeValue, false), Ui.tile("IDLE", idleValue, false)));
		p.add(Ui.gap(3));
		p.add(Ui.tileRow(Ui.tile("XP", xpValue, false), Ui.tile("XP/HR", xpHrValue, false)));
		p.add(Ui.gap(3));
		p.add(actionsRow);

		// sits in the slot the actions tiles vacate for combat, styled as a
		// tile so the row reads as "this is where a stat would be" rather
		// than as loose text after the block
		dependencyHintBox.setBackground(Palette.TILE);
		dependencyHintBox.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
		dependencyHintBox.add(dependencyHintLabel, BorderLayout.CENTER);
		dependencyHintBox.setVisible(false);
		p.add(Ui.fixHeight(dependencyHintBox));

		p.add(Ui.gap(3));
		p.add(killsRow);

		overflowLine.setBorder(BorderFactory.createEmptyBorder(5, 2, 0, 2));
		p.add(Ui.fixHeight(overflowLine));

		projectionPanel.setLayout(new BoxLayout(projectionPanel, BoxLayout.Y_AXIS));
		projectionPanel.setBackground(Palette.PANEL);
		projectionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(Ui.gap(8));
		p.add(projectionPanel);

		// the ACTIVE test moves onto the client thread with the mutation it
		// guards - reading state here and acting on it there would reopen
		// the same gap in miniature
		pauseResumeButton.addActionListener(e -> act(() -> {
			if (sessionManager.getState() == SessionState.ACTIVE)
			{
				sessionManager.pause();
			}
			else
			{
				sessionManager.resume();
			}
		}));
		stopButton.addActionListener(e -> act(sessionManager::stop));
		JPanel controls = new JPanel(new GridLayout(1, 2, 4, 0));
		controls.setOpaque(false);
		controls.add(pauseResumeButton);
		controls.add(stopButton);
		p.add(Ui.gap(8));
		p.add(Ui.fixHeight(controls));

		return wrapTop(p);
	}

	/**
	 * Runs a session mutation on the client thread, then repaints. The
	 * repaint is queued from here rather than from inside the hop so the
	 * panel stays responsive even if the client thread is busy; the next
	 * tick would refresh it anyway.
	 */
	private void act(Runnable mutation)
	{
		onClientThread.accept(mutation);
		onAction.run();
	}

	// ------------------------------------------------------------------
	// Refresh
	// ------------------------------------------------------------------

	void refresh()
	{
		SessionState state = sessionManager.getState();
		switch (state)
		{
			case PROMPTED:
				refreshPrompt();
				cardLayout.show(cards, PROMPT_CARD);
				break;
			case ACTIVE:
			case PAUSED:
			{
				ActivitySession session = sessionManager.getCurrentSession();
				if (session == null)
				{
					cardLayout.show(cards, IDLE_CARD);
					break;
				}
				refreshSession(session, state == SessionState.PAUSED);
				cardLayout.show(cards, SESSION_CARD);
				break;
			}
			default:
				cardLayout.show(cards, IDLE_CARD);
				break;
		}
	}

	private void refreshPrompt()
	{
		PromptSummary prompt = sessionManager.getPendingPrompt();
		if (prompt == null)
		{
			return;
		}
		promptTitle.setText(prompt.getSkill().getName() + " detected");
		promptDetail.setText(String.format("%d XP drops · %ds · +%,d XP",
			prompt.getDropCount(), prompt.getElapsedSeconds(), prompt.getTotalXp()));
		promptFootnote.setText("Ignoring hides " + prompt.getSkill().getName() + " prompts for a while.");
		promptIcon.setIcon(new ImageIcon(skillIconManager.getSkillImage(prompt.getSkill(), true)));
		promptCountdown.set(sessionManager.getPromptRemainingFraction(), Palette.BORDER, null, Palette.WELL);
	}

	/**
	 * Paused is the whole panel one step down in contrast plus a hard band -
	 * not just a button label change, which tested as too subtle to notice.
	 * The only full-strength element left is Resume.
	 */
	private void refreshSession(ActivitySession session, boolean paused)
	{
		Color primary = paused ? Palette.DIM : Palette.TEXT;
		Color secondary = paused ? Palette.DIMMEST : Palette.DIM;

		if (session.getSkill() != null)
		{
			headerIcon.setIcon(new ImageIcon(skillIconManager.getSkillImage(session.getSkill(), true)));
			skillLabel.setText(session.getSkill().getName());
		}
		skillLabel.setForeground(primary);
		activityLabel.setText(session.getActivity());
		activityLabel.setForeground(secondary);

		long total = sessionManager.getClock().getTotalSeconds();
		long active = sessionManager.getClock().getActiveSeconds();
		long idle = sessionManager.getClock().getIdleSeconds();
		int xp = session.getHeadlineXp();

		totalTimeLabel.setText(formatDuration(total));
		totalTimeLabel.setForeground(paused ? Palette.DIMMEST : Palette.TEXT);
		statusLabel.setText(paused ? "" : "RECORDING");

		pausedBand.setVisible(paused);
		if (paused)
		{
			// §13a: the two pauses look identical but end differently, and
			// which one you're in is the only thing the player needs to know -
			// an auto-pause lifts itself the moment they do anything, a
			// manual one waits for Resume however long that takes.
			pausedDetail.setText(sessionManager.isManuallyPaused()
				? formatShort(idle) + " · until you resume"
				: formatShort(idle) + " · not counted");
		}

		boolean combat = TrackingGroups.isCombatGroup(session.getSkill());
		double retention = session.getRetentionRate();
		boolean hasRetention = retention >= 0;
		retentionBlock.setVisible(hasRetention);
		if (hasRetention)
		{
			int generated = session.getTotalGenerated();
			int kept = session.getTotalNetRetained();
			retentionValue.setText(String.format("%.1f%%", retention * 100));
			retentionValue.setForeground(paused ? Palette.DIM : Palette.TEXT);
			// §31: for combat the same ratio is the *pickup* rate - what
			// share of what dropped you actually took - rather than how much
			// of your own output you kept. Same arithmetic, different
			// question, so it must not carry the same label.
			retentionCaption.setText(combat ? "PICKED UP" : "RETAINED");
			retentionCaption.setForeground(secondary);
			retentionBar.set(retention,
				paused ? Palette.ACCENT_MUTED : Palette.ACCENT,
				null,
				paused ? Palette.BORDER_PAUSED : Palette.BORDER);
			keptLabel.setText(String.format(combat ? "%,d taken" : "%,d kept", kept));
			keptLabel.setForeground(secondary);
			lostLabel.setText(String.format(combat ? "%,d left" : "%,d lost", Math.max(0, generated - kept)));
			lostLabel.setForeground(secondary);
		}

		refreshItemFlow(session.getItemFlow(), paused);

		activeValue.setText(formatDuration(active));
		activeValue.setForeground(primary);
		idleValue.setText(formatDuration(idle));
		idleValue.setForeground(secondary);
		xpValue.setText(String.format("+%,d", xp));
		xpValue.setForeground(primary);
		xpHrValue.setText(String.format("%,d", Math.round(session.getXpPerHour())));
		xpHrValue.setForeground(primary);

		// §40's actions pair. The design drops the duplicate "Logs / Logs per
		// hour" pair entirely, since it repeats this in almost every session.
		// combat measures kills, not hits (§37) - see recordActionIfMeaningful
		int actions = session.getActions();
		actionsRow.setVisible(!combat && actions > 0);
		if (actions > 0)
		{
			actionsValue.setText(String.format("%,d", actions));
			actionsValue.setForeground(primary);
			actionsHrValue.setText(String.format("%,d", Math.round(session.getActionsPerHour())));
			actionsHrValue.setForeground(primary);
		}

		// A combat session depends on two of RuneLite's own plugins being
		// switched on. Declaring them dependencies only makes them
		// injectable, not enabled. Say which one is missing, rather than
		// leaving kills at zero and the retention block absent with no
		// explanation - §37 [v9]: with the Loot Tracker off, nothing at all
		// is recorded for combat loot and the panel simply shows less.
		String missing = null;
		if (combat && !sessionManager.isLootTrackingVisible())
		{
			missing = "Enable RuneLite Loot Tracker plugin to record loot";
		}
		else if (combat && !sessionManager.isSlayerTaskVisible())
		{
			missing = "Enable RuneLite Slayer plugin to see KPH";
		}
		dependencyHintBox.setVisible(missing != null);
		if (missing != null)
		{
			dependencyHintLabel.setText("<html><body style='width:190px'>" + missing + "</body></html>");
		}

		int kills = session.getKills();
		killsRow.setVisible(kills > 0);
		if (kills > 0)
		{
			killsValue.setText(String.format("%,d", kills));
			killsValue.setForeground(primary);
			kphValue.setText(String.format("%.1f", session.getKillsPerHour()));
			kphValue.setForeground(primary);
		}

		overflowLine.setText(String.format("Total %s · overall %,d/hr",
			formatDuration(total), total > 0 ? Math.round(xp / (double) total * 3600) : 0));

		refreshProjection(session);

		pauseResumeButton.setText(paused ? "Resume" : "Pause");
		restyleButton(pauseResumeButton, paused);
	}

	/**
	 * One row per item acquired, with its game sprite. Gated on
	 * directlyAcquired rather than generated so a pickup-only item still
	 * appears.
	 */
	private void refreshItemFlow(Collection<ItemFlowEntry> entries, boolean paused)
	{
		List<ItemFlowEntry> visible = new ArrayList<>();
		Set<Integer> visibleIds = new LinkedHashSet<>();
		for (ItemFlowEntry entry : entries)
		{
			if (entry.getDirectlyAcquired() > 0)
			{
				visible.add(entry);
				visibleIds.add(entry.getItemId());
			}
		}

		if (!visibleIds.equals(renderedItemIds))
		{
			rebuildItemFlow(visible, visibleIds);
		}

		for (ItemFlowEntry entry : visible)
		{
			for (ItemRow row : itemRows)
			{
				if (row.itemId != entry.getItemId())
				{
					continue;
				}
				row.name.setText(itemNames.getOrDefault(entry.getItemId(), "Item #" + entry.getItemId()));
				row.name.setForeground(paused ? Palette.DIM : Palette.TEXT);
				row.sub.setText(subLine(entry));
				row.sub.setForeground(paused ? Palette.DIMMEST : Palette.DIM);
				row.net.setText(String.format("+%,d", entry.getNetRetained()));
				row.net.setForeground(paused ? Palette.ACCENT_MUTED : Palette.ACCENT);
			}
		}
	}

	private static String subLine(ItemFlowEntry entry)
	{
		int held = Math.max(0, entry.getNetRetained() - entry.getBanked());
		if (entry.getBanked() > 0 && held > 0)
		{
			return String.format("%,d banked · %,d held", entry.getBanked(), held);
		}
		if (entry.getBanked() > 0)
		{
			return String.format("%,d banked", entry.getBanked());
		}
		return String.format("%,d held", held);
	}

	private void rebuildItemFlow(List<ItemFlowEntry> visible, Set<Integer> visibleIds)
	{
		renderedItemIds = visibleIds;
		itemRows.clear();
		outputPanel.removeAll();

		for (ItemFlowEntry entry : visible)
		{
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setBackground(Palette.PANEL);
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.ROW_RULE),
				BorderFactory.createEmptyBorder(5, 2, 5, 2)));

			JLabel icon = iconHolder(ITEM_ICON);
			// AsyncBufferedImage loads off-thread and repaints the label
			// itself - the pattern LootTrackerBox uses, and why this is safe
			// here when getItemComposition is not.
			AsyncBufferedImage image = itemManager.getImage(entry.getItemId());
			if (image != null)
			{
				image.addTo(icon);
			}
			row.add(icon, BorderLayout.WEST);

			JLabel name = Ui.body("");
			JLabel sub = Ui.dim("");
			JPanel text = new JPanel();
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			text.setOpaque(false);
			text.add(name);
			text.add(sub);
			row.add(text, BorderLayout.CENTER);

			JLabel net = Ui.bold("", Palette.ACCENT);
			row.add(alignRight(net), BorderLayout.EAST);

			outputPanel.add(Ui.fixHeight(row));
			itemRows.add(new ItemRow(entry.getItemId(), name, sub, net));

			if (FutureXpResolver.hasChoice(entry.getItemId()))
			{
				outputPanel.add(buildUseSelector(entry.getItemId()));
			}
		}

		outputPanel.revalidate();
		outputPanel.repaint();
	}

	/**
	 * Inline per-item use selector (§33/§35), indented under its item so the
	 * association is positional rather than needing a label.
	 */
	private JPanel buildUseSelector(int itemId)
	{
		List<ItemUse> options = FutureXpResolver.getSelectableUses(itemId);
		JComboBox<ItemUse> combo = new JComboBox<>(new DefaultComboBoxModel<>(options.toArray(new ItemUse[0])));
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setForeground(Palette.TEXT);
		combo.setBackground(Palette.TILE);
		combo.setSelectedItem(itemUseStore.get(itemId));
		combo.setRenderer((list, value, index, selected, focused) -> {
			JLabel l = new JLabel(value == null ? "" : describeUse(value));
			l.setFont(FontManager.getRunescapeSmallFont());
			l.setOpaque(true);
			l.setBackground(selected ? Palette.BORDER : Palette.TILE);
			l.setForeground(Palette.TEXT);
			l.setBorder(BorderFactory.createEmptyBorder(1, 3, 1, 3));
			return l;
		});
		combo.addActionListener(e -> {
			ItemUse chosen = (ItemUse) combo.getSelectedItem();
			if (chosen != null)
			{
				itemUseStore.set(itemId, chosen);
				onAction.run();
			}
		});

		JPanel wrapper = new JPanel(new BorderLayout(4, 0));
		wrapper.setBackground(Palette.PANEL);
		wrapper.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, Palette.ROW_RULE),
			BorderFactory.createEmptyBorder(0, 40, 5, 2)));
		wrapper.add(Ui.label("use", FontManager.getRunescapeSmallFont(), Palette.DIMMEST), BorderLayout.WEST);
		wrapper.add(combo, BorderLayout.CENTER);
		return Ui.fixHeight(wrapper);
	}

	/**
	 * Each option names the concrete product and what it yields, the way
	 * banked-experience labels its activities ("Longbow (u) (10xp)").
	 * Without the number the choice is between bare words, and the whole
	 * point of picking a product is that it has a definite value (§35).
	 */
	private static String describeUse(ItemUse use)
	{
		if (use.skill == null || use.xpPerItem <= 0)
		{
			return use.label;
		}
		return String.format("%s (%s xp)", use.label,
			use.xpPerItem == Math.floor(use.xpPerItem)
				? String.valueOf((long) use.xpPerItem)
				: String.valueOf(use.xpPerItem));
	}

	/**
	 * SPEC.md §33: a projection must never be mistaken for an earned fact.
	 * It gets the dimmest tier, no bold, no accent, a leading tilde, an
	 * explicit "not earned" header, and its confidence stated. Everything
	 * factual on this panel is bold or orange; nothing projected ever is.
	 */
	private void refreshProjection(ActivitySession session)
	{
		projectionPanel.removeAll();

		// live view resolves against the current selection every tick; the
		// figure only becomes fixed when the session ends (§33)
		List<ProjectionBuilder.SkillTotal> rows =
			ProjectionBuilder.totals(ProjectionBuilder.build(session, itemUseStore));
		projectionPanel.setVisible(!rows.isEmpty());

		if (!rows.isEmpty())
		{
			JPanel header = new JPanel(new BorderLayout());
			header.setBackground(Palette.PANEL);
			header.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.BORDER),
				BorderFactory.createEmptyBorder(5, 0, 0, 0)));
			header.add(Ui.label("PROJECTED — NOT EARNED",
				FontManager.getRunescapeSmallFont(), Palette.DIMMEST), BorderLayout.WEST);
			projectionPanel.add(Ui.fixHeight(header));

			for (ProjectionBuilder.SkillTotal row : rows)
			{
				JPanel line = new JPanel(new BorderLayout());
				line.setBackground(Palette.PANEL);
				line.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 2));
				line.add(Ui.label(row.skill.getName(),
					FontManager.getRunescapeSmallFont(), Palette.DIM), BorderLayout.WEST);
				line.add(alignRight(Ui.label("~ +" + formatAbbreviated(row.xp),
					FontManager.getRunescapeSmallFont(), Palette.DIM)), BorderLayout.EAST);
				projectionPanel.add(Ui.fixHeight(line));

				JLabel note = Ui.label(row.confidence,
					FontManager.getRunescapeSmallFont(), Palette.DIMMEST);
				note.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
				projectionPanel.add(Ui.fixHeight(note));
			}
		}

		projectionPanel.revalidate();
		projectionPanel.repaint();
	}

	// ------------------------------------------------------------------
	// Formatting - see the design's overflow rules
	// ------------------------------------------------------------------

	private static String formatDuration(long totalSeconds)
	{
		return String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
	}

	private static String formatShort(long totalSeconds)
	{
		return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	/**
	 * Rates and projections abbreviate over 9,999; item quantities, retention
	 * and timers never do (the design's overflow rules).
	 */
	static String formatAbbreviated(double value)
	{
		if (value >= 1_000_000)
		{
			return String.format("%.2fm", value / 1_000_000);
		}
		if (value >= 10_000)
		{
			return String.format("%.1fk", value / 1_000);
		}
		return String.format("%,d", Math.round(value));
	}
}
