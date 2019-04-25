/*
 * Copyright (c) 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.mercurycli;

import net.fabricmc.mappings.*;
import org.cadixdev.bombe.type.signature.FieldSignature;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.io.MappingsReader;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.mercury.Mercury;
import org.cadixdev.mercury.remapper.MercuryRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class Main {

    public static class TinyReader extends MappingsReader {
        private final Mappings m;
        private final String from, to;
        private final boolean appendNone;

        private String procClassName(String s) {
            if (appendNone) {
                if (s.indexOf('/') < 0) {
                    return "none/" + s;
                } else {
                    return s;
                }
            } else {
                return s;
            }
        }

        public TinyReader(Mappings m, String from, String to, boolean appendNone) {
            this.m = m;
            this.from = from;
            this.to = to;
            this.appendNone = appendNone;
        }

        @Override
        public MappingSet read(final MappingSet mappings) {
            for (ClassEntry entry : m.getClassEntries()) {
                mappings.getOrCreateClassMapping(entry.get(from))
                        .setDeobfuscatedName(procClassName(entry.get(to)));
            }

            for (FieldEntry entry : m.getFieldEntries()) {
                EntryTriple fromEntry = entry.get(from);
                EntryTriple toEntry = entry.get(to);

                mappings.getOrCreateClassMapping(fromEntry.getOwner())
                        .getOrCreateFieldMapping(fromEntry.getName(), fromEntry.getDesc())
                        .setDeobfuscatedName(toEntry.getName());
            }

            for (MethodEntry entry : m.getMethodEntries()) {
                EntryTriple fromEntry = entry.get(from);
                EntryTriple toEntry = entry.get(to);

                mappings.getOrCreateClassMapping(fromEntry.getOwner())
                        .getOrCreateMethodMapping(fromEntry.getName(), fromEntry.getDesc())
                        .setDeobfuscatedName(toEntry.getName());
            }

            return mappings;
        }

        @Override
        public void close() throws IOException {

        }
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("Usage: mercury-tiny-cli [input directory] [output directory] [mapping file] [in name] [out name] (args / classpath entries...)");
            System.err.println("  - --appendnone appends none/ to the target class names if they are not in a package.");
            System.err.println("  - --gamename is used to provide a prefix for classes/fileds/methods present in the source mapping but not in the target mapping; by default, 'missing'.");
            System.exit(1);
        }

        boolean appendNone = false;
        List<File> classpath = new ArrayList<>();
        File gameJar = null;
        String gameName = "missing";

        for (int i = 5; i < args.length; i++) {
            String s = args[i].toLowerCase(Locale.ROOT);
            switch (s) {
                case "--appendnone":
                    appendNone = true;
                    break;
                case "--gamejar":
                    gameJar = new File(args[++i]);
                    gameName = args[++i].replace('.', '_').replace('-', '_').replace('/', '_');
                    break;
                default:
                    if (s.charAt(0) != '-') {
                        classpath.add(new File(args[i]));
                    }
                    break;
            }
        }

        MappingSet mappings;
        try (FileInputStream stream = new FileInputStream(new File(args[2]))){
            Mappings mappingsTiny = MappingsProvider.readTinyMappings(stream, false);
            mappings = new TinyReader(mappingsTiny, args[3], args[4], appendNone).read();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (gameJar != null) {
            // this is slow but whatever

            System.out.println("Checking with game JAR...");
            try (
                JarFile gameJarFile = new JarFile(gameJar)
            ) {
                Enumeration<JarEntry> entries = gameJarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        try (
                                InputStream stream = gameJarFile.getInputStream(entry)
                        ) {
                            ClassReader reader = new ClassReader(stream);
                            ClassNode node = new ClassNode();
                            reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

                            Optional<? extends ClassMapping<?, ?>> opt = mappings.getClassMapping(node.name);
                            if (opt.isPresent()) {
                                ClassMapping<?, ?> mapping = opt.get();
                                for (FieldNode fn : node.fields) {
                                    FieldSignature fo = FieldSignature.of(fn.name, fn.desc);
                                    if (!mapping.hasFieldMapping(fo)) {
                                        mapping.createFieldMapping(fo)
                                                .setDeobfuscatedName("XX_" + gameName + "_" + fn.name + "_XX");
                                    }
                                }

                                for (MethodNode mn : node.methods) {
                                    MethodSignature mo = MethodSignature.of(mn.name, mn.desc);
                                    if (!mapping.hasMethodMapping(mo)) {
                                        mapping.createMethodMapping(mo)
                                                .setDeobfuscatedName("XX_" + gameName + "_" + mn.name + "_XX");
                                    }
                                }
                            } else if (!node.name.contains("/") || (appendNone && node.name.startsWith("none"))) {
                                mappings.getOrCreateClassMapping(node.name)
                                        .setDeobfuscatedName((appendNone ? "none/" : "") + "XX_" + gameName + "_" + node.name.replace('/', '_') + "_XX");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println("Remapping...");

        Mercury mercury = new Mercury();

        for (File file : classpath) {
            mercury.getClassPath().add(file.toPath());
        }

        mercury.getProcessors().add(MercuryRemapper.create(mappings));

        File inDir = new File(args[0]);
        if (!inDir.exists() || !inDir.isDirectory()) {
            throw new RuntimeException("Input must be a directory!");
        }

        File outDir = new File(args[1]);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new RuntimeException("Creating output directory failed!");
            }
        } else if (!outDir.isDirectory()) {
            throw new RuntimeException("Output must be a directory!");
        }

        try {
            mercury.rewrite(inDir.toPath(), outDir.toPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
