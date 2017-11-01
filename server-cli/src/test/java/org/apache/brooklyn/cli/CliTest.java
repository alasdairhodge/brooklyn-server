/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.apache.brooklyn.api.catalog.CatalogItem;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.cli.AbstractMain.BrooklynCommand;
import org.apache.brooklyn.cli.AbstractMain.BrooklynCommandCollectingArgs;
import org.apache.brooklyn.cli.AbstractMain.DefaultInfoCommand;
import org.apache.brooklyn.cli.AbstractMain.HelpCommand;
import org.apache.brooklyn.cli.Main.AppShutdownHandler;
import org.apache.brooklyn.cli.Main.GeneratePasswordCommand;
import org.apache.brooklyn.cli.Main.LaunchCommand;
import org.apache.brooklyn.core.entity.AbstractApplication;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.StartableApplication;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.SimulatedLocation;
import org.apache.brooklyn.core.objs.proxy.EntityProxy;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.FatalConfigurationRuntimeException;
import org.apache.brooklyn.util.exceptions.UserFacingException;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import groovy.lang.GroovyClassLoader;
import io.airlift.airline.Cli;
import io.airlift.airline.Command;
import io.airlift.airline.ParseException;

public class CliTest {

    private static final Logger LOG = LoggerFactory.getLogger(CliTest.class);

    // See testInvokeGroovyScript test for usage
    public static final AtomicBoolean GROOVY_INVOKED = new AtomicBoolean(false);

    private ExecutorService executor;
    private Application app;
    private ManagementContext mgmt;
    private List<File> filesToDelete;

    private static volatile ExampleEntity exampleEntity;

    // static so that they can be set from the static classes ExampleApp and ExampleEntity
    private static volatile boolean exampleAppRunning;
    private static volatile boolean exampleAppConstructed;
    private static volatile boolean exampleEntityRunning;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        filesToDelete = Lists.newArrayList();
        executor = Executors.newCachedThreadPool();
        exampleAppConstructed = false;
        exampleAppRunning = false;
        exampleEntityRunning = false;
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        if (executor != null) executor.shutdownNow();
        if (app != null) Entities.destroyAll(app.getManagementContext());
        if (mgmt != null && mgmt.isRunning()) Entities.destroyAll(mgmt);
        if (exampleEntity != null && exampleEntity.getApplication() != null) Entities.destroyAll(exampleEntity.getApplication().getManagementContext());
        if (filesToDelete != null) {
            for (File file : filesToDelete) {
                file.delete();
            }
        }
        app = null;
    }
    
    @Test
    public void testLoadApplicationFromClasspath() throws Exception {
        String appName = ExampleApp.class.getName();
        Object appSpec = loadApplicationFromClasspathOrParse(appName);
        assertTrue(appSpec instanceof EntitySpec, "app="+appSpec);
        
        assertEquals(((EntitySpec<?>)appSpec).getImplementation(), ExampleApp.class);
    }

    @Test
    public void testLoadEntityFromClasspath() throws Exception {
        String entityName = ExampleEntity.class.getName();
        Object appSpec = loadApplicationFromClasspathOrParse(entityName);
        assertTrue(appSpec instanceof EntitySpec, "app="+appSpec);
        
        mgmt = LocalManagementContextForTests.newInstance();
        app = (Application) mgmt.getEntityManager().createEntity((EntitySpec<?>)appSpec);

        Collection<Entity> entities = app.getChildren();
        assertEquals(entities.size(), 1, "entities="+entities);
        assertTrue(Iterables.getOnlyElement(entities) instanceof ExampleEntity, "entities="+entities+"; ifs="+Iterables.getOnlyElement(entities).getClass().getInterfaces());
        assertTrue(Iterables.getOnlyElement(entities) instanceof EntityProxy, "entities="+entities);
    }

    @Deprecated // Tests deprecated approach of using impl directly
    @Test
    public void testLoadEntityImplFromClasspath() throws Exception {
        String entityName = ExampleEntityImpl.class.getName();
        Object appSpec = loadApplicationFromClasspathOrParse(entityName);
        assertTrue(appSpec instanceof EntitySpec, "app="+appSpec);
        
        mgmt = LocalManagementContextForTests.newInstance();
        app = (Application) mgmt.getEntityManager().createEntity((EntitySpec<?>)appSpec);
        
        Collection<Entity> entities = app.getChildren();
        assertEquals(entities.size(), 1, "entities="+entities);
        assertEquals(Iterables.getOnlyElement(entities).getEntityType().getName(), ExampleEntity.class.getCanonicalName(), "entities="+entities);
        assertTrue(Iterables.getOnlyElement(entities) instanceof EntityProxy, "entities="+entities);
    }

    private Object loadApplicationFromClasspathOrParse(String appName) throws Exception {
        LaunchCommand launchCommand = new Main.LaunchCommand();
        ResourceUtils resourceUtils = ResourceUtils.create(this);
        GroovyClassLoader loader = new GroovyClassLoader(CliTest.class.getClassLoader());
        return launchCommand.loadApplicationFromClasspathOrParse(resourceUtils, loader, appName);
    }
    
    @Test
    public void testInvokeGroovyScript() throws Exception {
        File groovyFile = File.createTempFile("testinvokegroovy", "groovy");
        try {
            String contents = CliTest.class.getCanonicalName()+".GROOVY_INVOKED.set(true);";
            Files.write(contents.getBytes(), groovyFile);

            LaunchCommand launchCommand = new Main.LaunchCommand();
            ResourceUtils resourceUtils = ResourceUtils.create(this);
            GroovyClassLoader loader = new GroovyClassLoader(CliTest.class.getClassLoader());
            launchCommand.execGroovyScript(resourceUtils, loader, groovyFile.toURI().toString());
            assertTrue(GROOVY_INVOKED.get());
            
        } finally {
            groovyFile.delete();
            GROOVY_INVOKED.set(false);
        }
    }
    
    @Test
    public void testStopAllApplications() throws Exception {
        LaunchCommand launchCommand = new Main.LaunchCommand();
        ManagementContext mgmt = LocalManagementContextForTests.newInstance();
        
        try {
            StartableApplication app = mgmt.getEntityManager().createEntity(EntitySpec.create(StartableApplication.class).impl(ExampleApp.class));
            ExampleApp appImpl = (ExampleApp) Entities.deproxy(app);
            SimulatedLocation loc = mgmt.getLocationManager().createLocation(LocationSpec.create(SimulatedLocation.class));
            app.start(ImmutableList.of(loc));
            assertTrue(appImpl.running);
            
            launchCommand.stopAllApps(ImmutableList.of(app));
            assertFalse(appImpl.running);
        } finally {
            // Stopping the app will make app.getManagementContext return the "NonDeploymentManagementContext";
            if (mgmt != null) Entities.destroyAll(mgmt);
        }
    }
    
    @Test
    public void testWaitsForInterrupt() throws Exception {
        final AppShutdownHandler listener = new AppShutdownHandler();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                listener.waitOnShutdownRequest();
            }});
        
        t.start();
        t.join(100);
        assertTrue(t.isAlive());
        
        t.interrupt();
        t.join(10*1000);
        assertFalse(t.isAlive());
    }

    protected Cli<BrooklynCommand> buildCli() {
        return new Main().cliBuilder().build();
    }
    
    @Test
    public void testLaunchCommandParsesArgs() throws ParseException {
        BrooklynCommand command = buildCli().parse("launch", 
                "--app", "my.App", 
                "--location", "localhost",
                "--port", "1234",
                "--bindAddress", "myhostname",
                "--noConsole", "--noConsoleSecurity", "--stopOnKeyPress", 
                "--localBrooklynProperties", "/path/to/myprops",
                LaunchCommand.PERSIST_OPTION, LaunchCommand.PERSIST_OPTION_REBIND, 
                "--persistenceDir", "/path/to/mypersist",
                LaunchCommand.HA_OPTION, LaunchCommand.HA_OPTION_STANDBY);
        assertTrue(command instanceof LaunchCommand, ""+command);
        String details = command.toString();
        assertTrue(details.contains("app=my.App"), details);   
        assertTrue(details.contains("script=null"), details);
        assertTrue(details.contains("location=localhost"), details);
        assertTrue(details.contains("port=1234"), details);
        assertTrue(details.contains("bindAddress=myhostname"), details);
        assertTrue(details.contains("noConsole=true"), details);
        assertTrue(details.contains("noConsoleSecurity=true"), details);
        assertTrue(details.contains("stopOnKeyPress=true"), details);
        assertTrue(details.contains("localBrooklynProperties=/path/to/myprops"), details);
        assertTrue(details.contains("persist=rebind"), details);
        assertTrue(details.contains("persistenceDir=/path/to/mypersist"), details);
        assertTrue(details.contains("highAvailability=standby"), details);
    }

    @Test
    public void testLaunchCommandUsesDefaults() throws ParseException {
        BrooklynCommand command = buildCli().parse("launch");
        assertTrue(command instanceof LaunchCommand, ""+command);
        String details = command.toString();
        assertTrue(details.contains("app=null"), details);   
        assertTrue(details.contains("script=null"), details);
        assertTrue(details.contains("location=null"), details);
        assertTrue(details.contains("port=null"), details);
        assertTrue(details.contains("noConsole=false"), details);
        assertTrue(details.contains("noConsoleSecurity=false"), details);
        assertTrue(details.contains("stopWhichAppsOnShutdown=theseIfNotPersisted"), details);
        assertTrue(details.contains("stopOnKeyPress=false"), details);
        assertTrue(details.contains("localBrooklynProperties=null"), details);
        assertTrue(details.contains("persist=disabled"), details);
        assertTrue(details.contains("persistenceDir=null"), details);
        assertTrue(details.contains("highAvailability=auto"), details);
    }

    @Test
    public void testLaunchCommandComplainsWithInvalidArgs() {
        Cli<BrooklynCommand> cli = buildCli();
        try {
            BrooklynCommand command = cli.parse("launch", "invalid");
            command.call();
            Assert.fail("Should have thrown exception; instead got "+command);
        } catch (ParseException e) {
            /* expected */
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    @Test
    public void testAppOptionIsOptional() throws ParseException {
        Cli<BrooklynCommand> cli = buildCli();
        cli.parse("launch", "blah", "my.App");
    }
    
    @Test
    public void testHelpCommand() {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("help");
        assertTrue(command instanceof HelpCommand, "Command is: "+command);
    }
    
    @Test
    public void testDefaultInfoCommand() {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse();
        assertTrue(command instanceof DefaultInfoCommand, "Command is: "+command);
    }

    @Test
    public void testCliSystemPropertyDefines() {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command0 = cli.parse(
            "-Dorg.apache.brooklyn.cli.CliTest.sample1=foo",
            "-Dorg.apache.brooklyn.cli.CliTest.sample2=bar",
            "launch", 
            "-Dorg.apache.brooklyn.cli.CliTest.sample3=baz"
            );
        assertTrue(command0 instanceof LaunchCommand);
        LaunchCommand command = (LaunchCommand) command0;
        assertEquals(command.getDefines().size(), 3, 
            "Command is: "+command);
        assertTrue(command.getDefines().get(0).equals("org.apache.brooklyn.cli.CliTest.sample1=foo"),  
            "Command is: "+command);
        assertTrue(command.getDefines().get(2).equals("org.apache.brooklyn.cli.CliTest.sample3=baz"), 
            "Command is: "+command);
        assertEquals(command.getDefinesAsMap().get("org.apache.brooklyn.cli.CliTest.sample3"), "baz",
            "Command is: "+command);
    }

    @Test
    public void testLaunchWritesOutApacheBrooklyn() throws Exception {
        InputStream origIn = System.in;
        PrintStream origOut = System.out;
        try {
            InputStream stdin = new ByteArrayInputStream("".getBytes());
            System.setIn(stdin);

            final ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            PrintStream stdout = new PrintStream(stdoutBytes);
            System.setOut(stdout);

            Cli<BrooklynCommand> cli = buildCli();
            BrooklynCommand command = cli.parse("launch", "--noConsole");
            submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                    @Override
                    public void run() {
                        String actualStdout = new String(stdoutBytes.toByteArray());
                        assertTrue(actualStdout.contains("Apache Brooklyn"), "stdout="+actualStdout);
                    }
                });
        
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
        }
    }
    
    @Test
    public void testLaunchWillStartAppWhenGivenImpl() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--app", ExampleApp.class.getName(), "--location", "localhost");
        submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                @Override
                public void run() {
                    assertTrue(exampleAppConstructed);
                    assertTrue(exampleAppRunning);
                }
            });
    }

    @Test
    public void testLaunchStartsYamlApp() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--app", "example-app-no-location.yaml", "--location", "localhost");
        submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                @Override
                public void run() {
                    assertTrue(exampleEntityRunning);
                }
            });
    }
    
    @Test
    public void testLaunchStartsYamlAppWithCommandLineLocation() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--app", "example-app-no-location.yaml", "--location", "localhost:(name=testLocalhost)");
        submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                @Override
                public void run() {
                    assertTrue(exampleEntityRunning);
                    assertTrue(Iterables.getOnlyElement(exampleEntity.getApplication().getLocations()).getDisplayName().equals("testLocalhost"));
                }
            });
    }
    
    @Test
    public void testLaunchStartsYamlAppWithYamlAppLocation() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--app", "example-app-app-location.yaml");
        submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                @Override
                public void run() {
                    assertTrue(exampleEntityRunning);
                    assertTrue(Iterables.getOnlyElement(exampleEntity.getApplication().getLocations()).getDisplayName().equals("appLocalhost"));
                }
            });
    }
    
    @Test
    public void testLaunchStartsYamlAppWithYamlAndAppCliLocation() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--app", "example-app-app-location.yaml", "--location", "localhost");
        submitCommandAndAssertRunnableSucceeds(command, new Runnable() {
                @Override
                public void run() {
                    assertTrue(exampleEntityRunning);
                    assertTrue(Iterables.getFirst(exampleEntity.getApplication().getLocations(), null).getDisplayName().equals("appLocalhost"));
                }
            });
    }

    @Test
    public void testInitialCatalog() throws Exception {
        final String itemName = "testAddToCatalog."+Identifiers.makeRandomId(8);
        final String itemVersion = "1.2.3";
        File bomFile = generateSimpleBomFile(itemName, itemVersion);

        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("launch", "--noConsole", "--catalogInitial", bomFile.getAbsolutePath());
        submitCommandAndAssertFunctionSucceeds(command, new Function<ManagementContext, Void>() {
                @Override
                public Void apply(ManagementContext mgmt) {
                    assertMgmtStartedEventually(mgmt);
                    CatalogItem<?, ?> item = mgmt.getCatalog().getCatalogItem(itemName, itemVersion);
                    assertNotNull(item);
                    return null;
                }
                private void assertMgmtStartedEventually(final ManagementContext mgmt) {
                    Asserts.succeedsEventually(new Runnable() {
                        @Override
                        public void run() {
                            assertTrue(mgmt.isStartupComplete());
                            assertEquals(mgmt.getNodeState(), ManagementNodeState.MASTER);
                        }});
                }
            });
    }
    
    private File generateSimpleBomFile(String itemName, String itemVersion) {
        String catalogContents = Joiner.on("\n").join(
                "brooklyn.catalog:",
                "  id: "+itemName,
                "  version: "+itemVersion,
                "  itemType: entity",
                "  item:",
                "    services:",
                "    - type: org.apache.brooklyn.entity.stock.BasicApplication");
        File bomFile = Os.writeToTempFile(new ByteArrayInputStream(catalogContents.getBytes()), "testAddToCatalog", ".bom");
        filesToDelete.add(bomFile);
        return bomFile;
    }
    
    @Test
    public void testGeneratePasswordCommandParsed() throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        BrooklynCommand command = cli.parse("generate-password", "--user", "myname");
        
        assertTrue(command instanceof GeneratePasswordCommand);
    }

    @Test
    public void testGeneratePasswordFromStdin() throws Exception {
        List<String> stdoutLines = runCommand(ImmutableList.of("generate-password", "--user", "myname", "--stdin"), "mypassword\nmypassword\n");
        
        System.out.println(stdoutLines);
    }

    @Test
    public void testGeneratePasswordFailsIfPasswordsDontMatch() throws Throwable {
        Throwable exception = runCommandExpectingException(ImmutableList.of("generate-password", "--user", "myname", "--stdin"), "mypassword\ndifferentpassword\n");
        if (exception instanceof UserFacingException && exception.toString().contains("Passwords did not match")) {
            // success
        } else {
            throw new Exception(exception);
        }
    }

    @Test
    public void testGeneratePasswordFailsIfNoConsole() throws Throwable {
        Throwable exception = runCommandExpectingException(ImmutableList.of("generate-password", "--user", "myname"), "");
        if (exception instanceof FatalConfigurationRuntimeException && exception.toString().contains("No console")) {
            // success
        } else {
            throw new Exception(exception);
        }
    }
    
    @Test
    public void testGeneratePasswordFailsIfPasswordBlank() throws Throwable {
        Throwable exception = runCommandExpectingException(ImmutableList.of("generate-password", "--user", "myname", "--stdin"), "\n\n");
        if (exception instanceof UserFacingException && exception.toString().contains("Password must not be blank")) {
            // success
        } else {
            throw new Exception(exception);
        }
    }

    @Test
    public void testInfoShowsDefaultBanner() throws Exception {
        List<String> stdoutLines = runCommand(ImmutableList.of("info"), "");
        
        for (String line : Splitter.on("\n").split(Main.DEFAULT_BANNER)) {
            assertTrue(stdoutLines.contains(line), "out="+stdoutLines);
        }
    }

    @Test
    public void testInfoSupportsCustomizedBanner() throws Exception {
        String origBanner = Main.banner;
        String origBannerFirstLine = Iterables.get(Splitter.on("\n").split(Main.DEFAULT_BANNER), 0);
        try {
            String customBanner = "My Custom Banner";
            Main.banner = customBanner;
            List<String> stdoutLines = runCommand(ImmutableList.of("info"), "");
            
            assertTrue(stdoutLines.contains(customBanner), "out="+stdoutLines);
            assertFalse(stdoutLines.contains(origBannerFirstLine), "out="+stdoutLines);
        } finally {
            Main.banner = origBanner;
        }
    }

    @Test
    public void testCanCustomiseInfoCommand() throws Exception {
        Main main = new Main() {
            @Override
            protected Class<? extends BrooklynCommand> cliInfoCommand() {
                return CustomInfoCommand.class;
            }
        };
        List<String> stdoutLines = runCommand(main.cliBuilder().build(), ImmutableList.of("info"), "");
        assertTrue(stdoutLines.contains("My Custom Info"), "out="+stdoutLines);
    }
    
    @Command(name = "info", description = "Display information about brooklyn")
    public static class CustomInfoCommand extends BrooklynCommandCollectingArgs {
        @Override
        public Void call() throws Exception {
            System.out.println("My Custom Info");
            return null;
        }
    }

    @Test
    public void testCanCustomiseLaunchCommand() throws Exception {
        Main main = new Main() {
            @Override
            protected Class<? extends BrooklynCommand> cliLaunchCommand() {
                return CustomLaunchCommand.class;
            }
        };
        List<String> stdoutLines = runCommand(main.cliBuilder().build(), ImmutableList.of("launch"), "");
        assertTrue(stdoutLines.contains("My Custom Launch"), "out="+stdoutLines);
    }
    
    @Command(name = "launch", description = "Starts a server, optionally with applications")
    public static class CustomLaunchCommand extends BrooklynCommandCollectingArgs {
        @Override
        public Void call() throws Exception {
            System.out.println("My Custom Launch");
            return null;
        }
    }

    protected Throwable runCommandExpectingException(Iterable<String> args, String input) throws Exception {
        try {
            List<String> stdout = runCommand(args, input);
            fail("Expected exception, but got stdout="+stdout);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    protected List<String> runCommand(Iterable<String> args, String input) throws Exception {
        Cli<BrooklynCommand> cli = buildCli();
        return runCommand(cli, args, input);
    }
    
    protected List<String> runCommand(Cli<BrooklynCommand> cli, Iterable<String> args, String input) throws Exception {
        final BrooklynCommand command = cli.parse(args);
        
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        Thread t= new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    command.call();
                } catch (Exception e) {
                    exception.set(e);
                    throw Exceptions.propagate(e);
                }
            }});
        
        InputStream origIn = System.in;
        PrintStream origOut = System.out;
        try {
            InputStream stdin = new ByteArrayInputStream(input.getBytes());
            System.setIn(stdin);

            ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            PrintStream stdout = new PrintStream(stdoutBytes);
            System.setOut(stdout);

            t.start();

            t.join(10*1000);
            assertFalse(t.isAlive());
            
            if (exception.get() != null) {
                throw new ExecutionException(exception.get());
            }
            
            return ImmutableList.copyOf(Splitter.on(Pattern.compile("\r?\n")).split(new String(stdoutBytes.toByteArray())));
        } finally {
            System.setIn(origIn);
            System.setOut(origOut);
            t.interrupt();
        }
    }

    void submitCommandAndAssertRunnableSucceeds(final BrooklynCommand command, final Runnable runnable) {
        submitCommandAndAssertFunctionSucceeds(command, new Function<ManagementContext, Void>() {
            @Override
            public Void apply(ManagementContext mgmt) {
                runnable.run();
                return null;
            }});
    }
    
    // Function usage is a convoluted way of letting some callers know which management context is 
    // being used, while others can just pass a runnable.
    void submitCommandAndAssertFunctionSucceeds(final BrooklynCommand command, final Function<ManagementContext, Void> function) {
        final AtomicReference<ManagementContext> mgmt = new AtomicReference<ManagementContext>();
        if (command instanceof LaunchCommand) {
            mgmt.set(LocalManagementContextForTests.newInstance());
            ((LaunchCommand)command).useManagementContext(mgmt.get());
        }
        try {
            executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try {
                        LOG.info("Calling command: "+command);
                        command.call();
                        return null;
                    } catch (Throwable t) {
                        LOG.error("Error executing command: "+t, t);
                        throw Exceptions.propagate(t);
                    }
                }});
    
            Runnable functionWrapper = new Runnable() {
                @Override
                public void run() {
                    function.apply(mgmt.get());
                }
            };
            Asserts.succeedsEventually(MutableMap.of("timeout", Duration.ONE_MINUTE), functionWrapper);
        } finally {
            if (mgmt.get() != null) Entities.destroyAll(mgmt.get());
        }
    }

    //  An empty app to be used for testing
    public static class ExampleApp extends AbstractApplication {
        volatile boolean running;
        volatile boolean constructed;
        
        @Override public void init() {
            super.init();
            constructed = true;
            exampleAppConstructed = true;
        }
        @Override public void start(Collection<? extends Location> locations) {
            super.start(locations);
            running = true;
            exampleAppRunning = true;
        }
        @Override public void stop() {
            super.stop();
            running = false;
            exampleAppRunning = false;
        }
    }
    
    // An empty entity to be used for testing
    @ImplementedBy(ExampleEntityImpl.class)
    public static interface ExampleEntity extends Entity, Startable {
    }   

    public static class ExampleEntityImpl extends AbstractEntity implements ExampleEntity {
        public ExampleEntityImpl() {
            super();
            exampleEntity = this;
        }
        @Override public void start(Collection<? extends Location> locations) {
            exampleEntityRunning = true;
        }
        @Override public void stop() {
            exampleEntityRunning = false;
        }
        @Override public void restart() {
        }
    }
}
