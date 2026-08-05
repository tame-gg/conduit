/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.conduit.luckperms;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.conduit.permission.ConduitPermissions;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes Conduit's known permission nodes into LuckPerms so they autocomplete in {@code /lpv}
 * and the web editor.
 *
 * <h3>Why this exists</h3>
 * LuckPerms does not invent permission catalogues — it only suggests nodes it has <em>seen
 * checked</em> (or that plugins inserted into its internal {@code PermissionRegistry}). Velocity
 * has no Bukkit-style permission registration API, so nodes that Conduit only checks rarely —
 * notably {@code conduit.maintenance.bypass}, which is evaluated only while maintenance mode is
 * active — never appear for autofill. Operators then assume the node does not exist.
 *
 * <h3>How seeding works</h3>
 * <ol>
 *   <li>Prefer LuckPerms' own registry {@code insert(String)} (the same path LP uses for its
 *       command permissions at load), reached via the loaded plugin instance. Reflection keeps
 *       Conduit free of a compile-time LuckPerms dependency.</li>
 *   <li>If the registry is unreachable, fall back to checking each node against the console
 *       {@link CommandSource}. When LuckPerms owns console permissions, that check still feeds
 *       the suggestion tree through LP's monitored calculator.</li>
 * </ol>
 *
 * <p>Safe no-op when LuckPerms is not installed or the API shape changes.
 */
public final class LuckPermsPermissionSeeder {

  private static final Logger logger = LogManager.getLogger(LuckPermsPermissionSeeder.class);

  /** Common Velocity / standalone plugin ids LuckPerms has used. */
  private static final String[] LUCKPERMS_PLUGIN_IDS = {"luckperms", "LuckPerms"};

  private LuckPermsPermissionSeeder() {}

  /**
   * Seeds every node in {@link ConduitPermissions#all()} into LuckPerms when it is present.
   *
   * @param proxy the running proxy (plugins must already be loaded)
   * @return how many nodes were published, or {@code 0} if LuckPerms was absent / unreachable
   */
  public static int seed(ProxyServer proxy) {
    return seed(proxy, ConduitPermissions.all());
  }

  /**
   * Seeds the given permission nodes into LuckPerms when it is present.
   *
   * @param proxy       the running proxy
   * @param permissions permission strings to publish
   * @return how many nodes were published, or {@code 0} if LuckPerms was absent / unreachable
   */
  public static int seed(ProxyServer proxy, Collection<String> permissions) {
    if (permissions == null || permissions.isEmpty()) {
      return 0;
    }

    Optional<Object> luckPermsPlugin = findLuckPermsPlugin(proxy.getPluginManager());
    if (luckPermsPlugin.isEmpty()) {
      logger.debug("[Conduit] LuckPerms not loaded; skipping permission suggestion seed.");
      return 0;
    }

    int inserted = seedViaRegistry(luckPermsPlugin.get(), permissions);
    if (inserted > 0) {
      logger.info("[Conduit] Seeded {} Conduit permission(s) into LuckPerms suggestions "
          + "(including conduit.maintenance.bypass).", inserted);
      return inserted;
    }

    int checked = seedViaConsoleChecks(proxy.getConsoleCommandSource(), permissions);
    if (checked > 0) {
      logger.info("[Conduit] Seeded {} Conduit permission(s) into LuckPerms via console checks.",
          checked);
    } else {
      logger.debug("[Conduit] Could not seed LuckPerms permission suggestions.");
    }
    return checked;
  }

  /**
   * Reflectively calls {@code getPermissionRegistry().insert(permission)} on the LuckPerms plugin
   * instance. Package-private for unit tests.
   */
  static int seedViaRegistry(Object luckPermsPlugin, Collection<String> permissions) {
    try {
      Method getRegistry = findMethod(luckPermsPlugin.getClass(), "getPermissionRegistry");
      if (getRegistry == null) {
        return 0;
      }
      getRegistry.setAccessible(true);
      Object registry = getRegistry.invoke(luckPermsPlugin);
      if (registry == null) {
        return 0;
      }

      Method insert = findMethod(registry.getClass(), "insert", String.class);
      if (insert == null) {
        // AsyncPermissionRegistry queues via offer(); insert() still exists on the parent.
        insert = findMethod(registry.getClass(), "offer", String.class);
      }
      if (insert == null) {
        return 0;
      }
      insert.setAccessible(true);

      int count = 0;
      for (String permission : permissions) {
        if (permission == null || permission.isBlank()) {
          continue;
        }
        insert.invoke(registry, permission);
        count++;
      }
      return count;
    } catch (ReflectiveOperationException | RuntimeException e) {
      logger.debug("[Conduit] LuckPerms registry seed failed: {}", e.toString());
      return 0;
    }
  }

  /** Package-private for unit tests. */
  static int seedViaConsoleChecks(PermissionProbe probe, Collection<String> permissions) {
    if (probe == null) {
      return 0;
    }
    int count = 0;
    for (String permission : permissions) {
      if (permission == null || permission.isBlank()) {
        continue;
      }
      try {
        probe.hasPermission(permission);
        count++;
      } catch (RuntimeException e) {
        logger.debug("[Conduit] Console permission seed failed for '{}': {}", permission,
            e.toString());
      }
    }
    return count;
  }

  private static int seedViaConsoleChecks(CommandSource console, Collection<String> permissions) {
    return seedViaConsoleChecks(console::hasPermission, permissions);
  }

  /**
   * Minimal permission-check probe used by the console fallback path (and tests).
   */
  @FunctionalInterface
  interface PermissionProbe {
    boolean hasPermission(String permission);
  }

  private static Optional<Object> findLuckPermsPlugin(PluginManager pluginManager) {
    for (String id : LUCKPERMS_PLUGIN_IDS) {
      Optional<Object> instance = pluginManager.getPlugin(id)
          .flatMap(PluginContainer::getInstance);
      if (instance.isPresent()) {
        return instance;
      }
    }
    // Case-insensitive fallback for forks that rename the plugin id.
    for (PluginContainer container : pluginManager.getPlugins()) {
      String id = container.getDescription().getId();
      if (id != null && id.toLowerCase(Locale.ROOT).contains("luckperms")) {
        Optional<?> instance = container.getInstance();
        if (instance.isPresent()) {
          return Optional.of(instance.get());
        }
      }
    }
    return Optional.empty();
  }

  private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
    try {
      return type.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException ignored) {
      // Fall through to declared-method walk for non-public members.
    }
    Class<?> current = type;
    while (current != null && current != Object.class) {
      try {
        return current.getDeclaredMethod(name, parameterTypes);
      } catch (NoSuchMethodException ignored) {
        // walk superclass / interfaces
      }
      for (Class<?> iface : current.getInterfaces()) {
        try {
          return iface.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
          // continue
        }
      }
      current = current.getSuperclass();
    }
    return null;
  }
}
