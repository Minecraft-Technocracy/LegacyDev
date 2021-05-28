/*
 * LegacyDev
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.legacydev;

import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {
    static Logger LOGGER = setupLogger();

    private static Logger setupLogger() {
        InputStream is = Main.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Logger.getLogger("LegacyDev");
    }

    protected void start(String[] args) throws ClassNotFoundException, NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        String natives = getenv("nativesDirectory");
        if (natives != null ) {
            LOGGER.info("Natives: " + natives);
            handleNatives(natives);
        }

        String mainClass = getenv("mainClass");
        if (mainClass == null)
            throw new IllegalArgumentException("Must specify mainClass environment variable");
        LOGGER.info("Main Class: " + mainClass);

        String srg2mcp = getenv("MCP_TO_SRG");
        if (srg2mcp != null) {
            LOGGER.info("Srg2Mcp: " + srg2mcp);
            System.setProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", srg2mcp);
        }

        String[] cleanArgs = parseArgs(args);

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int x = 0; x < cleanArgs.length; x++) {
            b.append(cleanArgs[x]);
            if ("--accessToken".equalsIgnoreCase(cleanArgs[x])) {
                b.append(", {REDACTED}");
                x++;
            }

            if (x < cleanArgs.length - 1)
                b.append(", ");
        }
        b.append(']');
        LOGGER.info("Running with arguments: " + b.toString());

        condenseModClasses();

        Class<?> cls = Class.forName(mainClass);
        Method main = cls.getDeclaredMethod("main", String[].class);
        main.invoke(null, new Object[] { cleanArgs });
    }

    protected void handleNatives(String path) { }

    protected Map<String, String> getDefaultArguments() {
        return new LinkedHashMap<>();
    }

    private String[] parseArgs(String[] args) {
        final Map<String, String> defaults = getDefaultArguments();

        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        for (String key : defaults.keySet())
            parser.accepts(key).withRequiredArg().ofType(String.class);

        final NonOptionArgumentSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        for (String key : defaults.keySet()) {
            if (options.hasArgument(key)) {
                String value = (String) options.valueOf(key);
                defaults.put(key, value);
                if (!"password".equalsIgnoreCase(key) && !"accessToken".equalsIgnoreCase(key))
                    LOGGER.info(key + ": " + value);
            }
        }

        List<String> extras = new ArrayList<>(nonOption.values(options));
        LOGGER.info("Extra: " + extras);

        List<String> lst = new ArrayList<>();
        defaults.forEach((k,v) -> {
            if (!nullOrEmpty(v)) {
                lst.add("--" + k);
                lst.add(v);
            }
        });

        String tweak = getenv("tweakClass");
        if (!nullOrEmpty(tweak)) {
            lst.add("--tweakClass");
            lst.add(tweak);
        }

        lst.addAll(extras);

        return lst.toArray(new String[lst.size()]);
    }

    protected void condenseModClasses() {
        try {
            String modClasses = getenv("MOD_CLASSES");
            List<Path> dirs = Arrays.stream(modClasses.split(";")).map(Paths::get).collect(Collectors.toList());
            if (dirs.size() <= 1)
                return;
            URLClassLoader classloader = (URLClassLoader) getClass().getClassLoader();
            Object ucp = getDeclaredField(classloader, "ucp");
            List<URL> urls = (List<URL>) getDeclaredField(ucp, "path");
            Path base = getBasePath(dirs);
            for (Path dir : dirs) {
                if (dir == base)
                    continue;
                urls.remove(dir.toUri().toURL());
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path source : walk.sorted().collect(Collectors.toList())) {
                        if (source == dir)
                            continue;
                        if (Files.isDirectory(source)) {
                            base.resolve(dir.relativize(source)).toFile().mkdirs();
                        } else {
                            Files.copy(source, base.resolve(dir.relativize(source)), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Error when condensing mod output directories", e);
            e.printStackTrace();
        }
    }

    protected Object getDeclaredField(Object in, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field field = in.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(in);
    }

    protected Path getBasePath(List<Path> dirs) {
        for (int i = 0; i < dirs.size(); i++) {
            Path dir = dirs.get(i);
            if (dir.getFileName().toString().equals("classes")) {
                return dir;
            }
            if (i == dirs.size() - 1)
                return dir;
        }
        return null;
    }

    protected String getenv(String name) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? null : value;
    }

    protected boolean nullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
