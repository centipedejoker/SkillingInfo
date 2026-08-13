package com.skillinginfo.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Local JSON Lines persistence (SPEC.md §44, §46). One completed session per
 * line, appended - never rewritten - so a crash or corrupt line can't lose
 * prior history. Corrupt lines are skipped on load rather than failing the
 * whole read.
 * <p>
 * Scoped per account hash - not a single global file - so switching between
 * accounts (main/alt, GIM members) doesn't merge their skilling history. The
 * account is set by {@link #useAccount(long)} rather than read from the
 * client on every call, which is what lets the reads and writes run off the
 * client thread (§44 `[v9]`).
 */
@Slf4j
public class SessionRepository
{
	private static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
		.registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, ctx) -> Instant.parse(json.getAsString()))
		.create();

	private volatile File file;

	/**
	 * `[v9]` Points the repository at an account's file.
	 * <p>
	 * The account hash is passed in rather than read from {@code Client} here,
	 * so that reading and writing can happen off the client thread without
	 * touching the client at all (§44 `[v9]`). Call this from the client
	 * thread whenever the account changes (§5 `[v9]`).
	 */
	public void useAccount(long accountHash)
	{
		String accountKey = accountHash <= 0 ? "unknown" : String.valueOf(accountHash);
		File dir = new File(new File(RuneLite.RUNELITE_DIR, "skilling-info"), accountKey);
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Unable to create Skilling Info data directory: {}", dir);
		}
		file = new File(dir, "sessions.jsonl");
	}

	private File resolveFile()
	{
		if (file == null)
		{
			useAccount(0);
		}
		return file;
	}

	public void append(ActivitySession session)
	{
		File file = resolveFile();
		try (Writer writer = new FileWriter(file, StandardCharsets.UTF_8, true))
		{
			writer.write(GSON.toJson(session));
			writer.write(System.lineSeparator());
		}
		catch (IOException e)
		{
			log.warn("Failed to persist session {}", session.getId(), e);
		}
	}

	/**
	 * @return completed sessions, most recent first.
	 */
	public List<ActivitySession> loadAll()
	{
		File file = resolveFile();
		List<ActivitySession> sessions = new ArrayList<>();
		if (!file.exists())
		{
			return sessions;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				try
				{
					sessions.add(GSON.fromJson(line, ActivitySession.class));
				}
				catch (Exception e)
				{
					log.warn("Skipping corrupt session record", e);
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to read session history", e);
		}

		Collections.reverse(sessions);
		return sessions;
	}
}
