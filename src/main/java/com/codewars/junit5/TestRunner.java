package com.codewars.junit5;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.junit.platform.commons.JUnitException;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.launcher.core.LauncherFactory;

public class TestRunner {
  private List<Path> classpathEntries = emptyList();

  // Runs all tests found in given classpaths
  // `java -jar junit-runner.jar
  // './jars/*:./classes/java/main:./classe/java/test'`
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Missing classpath");
      System.exit(1);
    }

    // There's some race in shutdown hooks registered by Spring Boot.
    // HACK Don't run shutdown hooks to avoid the following exception:
    // Exception in thread "SpringContextShutdownHook"
    // java.lang.NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy
    int status = new TestRunner(args[0]).execute();
    Runtime.getRuntime().halt(status);
    // System.exit(status);
  }

  private TestRunner(String classPath) {
    this.classpathEntries = Arrays.stream(classPath.split(File.pathSeparator))
        .flatMap(this::expandWildcard)
        .collect(toList());
  }

  private int execute() throws Exception {
    return callWithCustomClassLoader(() -> executeTests());
  }

  private int executeTests() {
    var listener = new CodewarsListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    var discoveryRequest = request().selectors(createClasspathRootSelectors()).build();
    launcher.execute(discoveryRequest);
    if (listener.testCount() == 0) { // no tests found
      return 2;
    }
    if (listener.failures() > 0) {
      return 1;
    }
    return 0;
  }

  private List<ClasspathRootSelector> createClasspathRootSelectors() {
    var rootDirs = new LinkedHashSet<>(getAllClasspathRootDirectories());
    var dirs = this.classpathEntries.stream().filter(Files::isDirectory).filter(Files::exists)
        .collect(toList());
    rootDirs.addAll(dirs);
    return selectClasspathRoots(rootDirs);
  }

  private Optional<ClassLoader> createCustomClassLoader() {
    var entries = this.classpathEntries.stream().filter(Files::exists)
        .collect(toList());
    if (!entries.isEmpty()) {
      var urls = entries.stream().map(this::toURL).toArray(URL[]::new);
      return Optional.of(URLClassLoader.newInstance(urls, getDefaultClassLoader()));
    }
    return Optional.empty();
  }

  private URL toURL(Path path) {
    try {
      return path.toUri().toURL();
    } catch (Exception ex) {
      throw new JUnitException("Invalid classpath entry: " + path, ex);
    }
  }

  private Stream<Path> expandWildcard(String spath) {
    if (!spath.endsWith("*")) {
      return Stream.of(Paths.get(spath));
    }

    try {
      var path = Paths.get(spath.substring(0, spath.length() - 1));
      if (path.toFile().isDirectory()) {
        return Files.list(path).filter(p -> p.getFileName().toString().endsWith(".jar"));
      }
      // Invalid entry
      return Stream.empty();
    } catch (IOException e) {
      e.printStackTrace();
      return Stream.empty();
    }
  }

  private Set<Path> getAllClasspathRootDirectories() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(Paths::get)
        .filter(Files::isDirectory)
        .collect(toSet());
  }

  private ClassLoader getDefaultClassLoader() {
    try {
      var contextClassLoader = Thread.currentThread().getContextClassLoader();
      if (contextClassLoader != null) {
        return contextClassLoader;
      }
    } catch (Throwable t) {
      // ignore
    }
    return ClassLoader.getSystemClassLoader();
  }

  <T> T callWithCustomClassLoader(Callable<T> callable) throws Exception {
    var customClassLoader = createCustomClassLoader();
    if (!customClassLoader.isPresent()) {
      return callable.call();
    }

    var loader = customClassLoader.get();
    var originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(loader);
      return callable.call();
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
      if (loader instanceof AutoCloseable) {
        ((AutoCloseable) loader).close();
      }
    }
  }
}
