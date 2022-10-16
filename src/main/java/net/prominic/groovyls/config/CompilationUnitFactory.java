////////////////////////////////////////////////////////////////////////////////
// Copyright 2022 Prominic.NET, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Prominic.NET, Inc.
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package net.prominic.groovyls.config;

import groovy.lang.GroovyClassLoader;
import net.prominic.groovyls.compiler.control.GroovyLSCompilationUnit;
import net.prominic.groovyls.compiler.control.io.StringReaderSourceWithURI;
import net.prominic.groovyls.util.FileContentsTracker;
import org.apache.groovy.parser.antlr4.util.StringUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CompilationUnitFactory implements ICompilationUnitFactory {
    private static final String FILE_EXTENSION_GROOVY = ".groovy";

    private GroovyLSCompilationUnit compilationUnit;
    private CompilerConfiguration config;
    private GroovyClassLoader classLoader;
    private List<String> additionalClasspathList;

    public CompilationUnitFactory() {
    }

    public List<String> getAdditionalClasspathList() {
        return additionalClasspathList;
    }

    public void setAdditionalClasspathList(List<String> additionalClasspathList) {
        this.additionalClasspathList = additionalClasspathList;
        invalidateCompilationUnit();
    }

    public void invalidateCompilationUnit() {
        compilationUnit = null;
        config = null;
        classLoader = null;
    }

    public GroovyLSCompilationUnit create(Path workspaceRoot, FileContentsTracker fileContentsTracker) {
        if (config == null) {
            config = getConfiguration();
        }

        if (classLoader == null) {
            classLoader = new GroovyClassLoader(ClassLoader.getSystemClassLoader().getParent(), config, true);
        }

        Set<URI> changedUris = fileContentsTracker.getChangedURIs();
        if (compilationUnit == null) {
            compilationUnit = new GroovyLSCompilationUnit(config, null, classLoader);
            // we don't care about changed URIs if there's no compilation unit yet
            changedUris = null;
        } else {
            compilationUnit.setClassLoader(classLoader);
            final Set<URI> urisToRemove = changedUris;
            List<SourceUnit> sourcesToRemove = new ArrayList<>();
            compilationUnit.iterator().forEachRemaining(sourceUnit -> {
                URI uri = sourceUnit.getSource().getURI();
                if (urisToRemove.contains(uri)) {
                    sourcesToRemove.add(sourceUnit);
                }
            });
            // if an URI has changed, we remove it from the compilation unit so
            // that a new version can be built from the updated source file
            compilationUnit.removeSources(sourcesToRemove);
        }

        if (workspaceRoot != null) {
            addDirectoryToCompilationUnit(workspaceRoot, compilationUnit, fileContentsTracker, changedUris);
        } else {
            final Set<URI> urisToAdd = changedUris;
            fileContentsTracker.getOpenURIs().forEach(uri -> {
                // if we're only tracking changes, skip all files that haven't
                // actually changed
                if (urisToAdd != null && !urisToAdd.contains(uri)) {
                    return;
                }
                String contents = fileContentsTracker.getContents(uri);
                addOpenFileToCompilationUnit(uri, contents, compilationUnit);
            });
        }

        return compilationUnit;
    }

    protected CompilerConfiguration getConfiguration() {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setSourceEncoding(CompilerConfiguration.DEFAULT_SOURCE_ENCODING);
        Map<String, Boolean> optimizationOptions = new HashMap<>();
        optimizationOptions.put(CompilerConfiguration.GROOVYDOC, true);
        optimizationOptions.put(CompilerConfiguration.RUNTIME_GROOVYDOC, true);
        config.setOptimizationOptions(optimizationOptions);

        List<String> classpathList = new ArrayList<>();
        // 加载本地环境包
        String libPath = System.getProperty("lsp.libPath");
        if (!StringUtils.isEmpty(libPath)) {
            File directory = new File(libPath);
            if (directory.isDirectory() && directory.exists()) {
                File[] files = directory.listFiles();
                for (File file : files) {
                    if (file.getName().endsWith(".jar") && file.exists() && file.isFile()) {
                        classpathList.add(file.getPath());
                    }
                }
            }
        }
        getClasspathList(classpathList);
        config.setClasspathList(classpathList);

        return config;
    }

    protected void getClasspathList(List<String> result) {
        if (additionalClasspathList == null) {
            return;
        }

        for (String entry : additionalClasspathList) {
            boolean mustBeDirectory = false;
            if (entry.endsWith("*")) {
                entry = entry.substring(0, entry.length() - 1);
                mustBeDirectory = true;
            }

            File file = new File(entry);
            if (!file.exists()) {
                continue;
            }
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    if (!child.getName().endsWith(".jar") || !child.isFile()) {
                        continue;
                    }
                    result.add(child.getPath());
                }
            } else if (!mustBeDirectory && file.isFile()) {
                if (file.getName().endsWith(".jar")) {
                    result.add(entry);
                }
            }
        }
    }

    protected void addDirectoryToCompilationUnit(Path dirPath, GroovyLSCompilationUnit compilationUnit,
                                                 FileContentsTracker fileContentsTracker, Set<URI> changedUris) {
        try {
            if (Files.exists(dirPath)) {
                Files.walk(dirPath).forEach((filePath) -> {
                    if (!filePath.toString().endsWith(FILE_EXTENSION_GROOVY)) {
                        return;
                    }
                    URI fileURI = filePath.toUri();
                    if (!fileContentsTracker.isOpen(fileURI)) {
                        File file = filePath.toFile();
                        if (file.isFile()) {
                            if (changedUris == null || changedUris.contains(fileURI)) {
                                compilationUnit.addSource(file);
                            }
                        }
                    }
                });
            }

        } catch (IOException e) {
            System.err.println("Failed to walk directory for source files: " + dirPath);
        }
        fileContentsTracker.getOpenURIs().forEach(uri -> {
            Path openPath = Paths.get(uri);
            if (!openPath.normalize().startsWith(dirPath.normalize())) {
                return;
            }
            if (changedUris != null && !changedUris.contains(uri)) {
                return;
            }
            String contents = fileContentsTracker.getContents(uri);
            addOpenFileToCompilationUnit(uri, contents, compilationUnit);
        });
    }

    protected void addOpenFileToCompilationUnit(URI uri, String contents, GroovyLSCompilationUnit compilationUnit) {
        URI path = uri;
        try {
            if ("inmemory".equals(uri.getScheme())) {
                String url = uri.toString().replace("inmemory", "file") + ".groovy";
                path = new URI(url);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Path filePath = Paths.get(path);
        SourceUnit sourceUnit = new SourceUnit(filePath.toString(),
                new StringReaderSourceWithURI(contents, uri, compilationUnit.getConfiguration()),
                compilationUnit.getConfiguration(), compilationUnit.getClassLoader(),
                compilationUnit.getErrorCollector());
        compilationUnit.addSource(sourceUnit);
    }
}