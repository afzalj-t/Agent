/*******************************************************************************
 * Copyright (c) 2019 Edgeworx, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Saeid Baghbidi
 * Kilton Hopkins
 * Neha Naithani
 *******************************************************************************/
package org.eclipse.iofog.resource_consumption_manager;

import org.bouncycastle.math.ec.ECCurve;
import org.eclipse.iofog.command_line.util.CommandShellExecutor;
import org.eclipse.iofog.command_line.util.CommandShellResultSet;
import org.eclipse.iofog.utils.Constants;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Agent Exception
 *
 * @author nehanaithani
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ResourceConsumptionManager.class, LoggingService.class,
        Configuration.class, CommandShellExecutor.class})
public class ResourceConsumptionManagerTest {
    private ResourceConsumptionManager resourceConsumptionManager;
    private static final String MODULE_NAME = "Resource Consumption Manager";
    private File dir = null;
    private File tagFile = null;
    private CommandShellResultSet<List<String>, List<String>> resultSetWithPath;
    private List<String> error;
    private List<String> value;
    @Before
    public void setUp() throws Exception {
        resourceConsumptionManager = ResourceConsumptionManager.getInstance();
        PowerMockito.mockStatic(LoggingService.class);
        PowerMockito.mockStatic(Configuration.class);
        PowerMockito.mockStatic(CommandShellExecutor.class);
        when(Configuration.getMemoryLimit()).thenReturn(1.0f);
        when(Configuration.getCpuLimit()).thenReturn(1.0f);
        when(Configuration.getDiskLimit()).thenReturn(1.0f);
        when(Configuration.getDiskDirectory()).thenReturn("");
    }

    @After
    public void tearDown() throws Exception {
        resourceConsumptionManager = null;
        if(tagFile != null && tagFile.exists()){
            tagFile.delete();

        }
        if(dir != null && dir.exists()){
            dir.delete();
        }
    }

    /**
     * Asserts module index of resource manager is equal to constant value
     */
    @Test
    public void testGetModuleIndex() {
        assertEquals(Constants.RESOURCE_CONSUMPTION_MANAGER, resourceConsumptionManager.getModuleIndex());
    }

    /**
     *  Asserts module name of resource manager is equal to constant value
     */
    @Test
    public void testGetModuleName() {
        assertEquals("Resource Consumption Manager", resourceConsumptionManager.getModuleName());
    }

    /**
     * Asserts mock is same as the StraceDiagnosticManager.getInstance()
     */
    @Test
    public void testGetInstanceIsSameAsMock() {
        resourceConsumptionManager = mock(ResourceConsumptionManager.class);
        PowerMockito.mockStatic(ResourceConsumptionManager.class);
        when(ResourceConsumptionManager.getInstance()).thenReturn(resourceConsumptionManager);
        assertSame(resourceConsumptionManager, ResourceConsumptionManager.getInstance());
    }

    /**
     * Asserts instance config updates
     */
    @Test
    public void testInstanceConfigUpdated() throws Exception{
        Field privateDiskLimitField = ResourceConsumptionManager.class.
                getDeclaredField("diskLimit");
        Field privateCpuLimitField = ResourceConsumptionManager.class.
                getDeclaredField("cpuLimit");
        Field privateMemoryLimitField = ResourceConsumptionManager.class.
                getDeclaredField("memoryLimit");
        privateDiskLimitField.setAccessible(true);
        privateCpuLimitField.setAccessible(true);
        privateMemoryLimitField.setAccessible(true);
        assertEquals(0.0f, privateDiskLimitField.get(resourceConsumptionManager));
        assertEquals(0.0f, privateCpuLimitField.get(resourceConsumptionManager));
        assertEquals(0.0f, privateMemoryLimitField.get(resourceConsumptionManager));
        resourceConsumptionManager.instanceConfigUpdated();
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start Configuration instance updated");
        LoggingService.logInfo(MODULE_NAME,
                "Finished Config updated");
        assertEquals(1.0E9f, privateDiskLimitField.get(resourceConsumptionManager));
        assertEquals(1.0f, privateCpuLimitField.get(resourceConsumptionManager));
        assertEquals(1000000.0f, privateMemoryLimitField.get(resourceConsumptionManager));
        privateDiskLimitField.setAccessible(false);
        privateCpuLimitField.setAccessible(false);
        privateMemoryLimitField.setAccessible(false);

    }

    /**
     * Test start method
     */
    @Test
    public void testStartThread() {
        resourceConsumptionManager.start();
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Starting");
        LoggingService.logInfo(MODULE_NAME,
                "started");
    }

    /**
     * Test DirectorySize method when file don't exist then return length 0
     */
    @Test
    public void testDirectorySizeWhenFileDontExist() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("directorySize", String.class);
        method.setAccessible(true);
        long output = (long) method.invoke(resourceConsumptionManager, "file");
        assertEquals(0f, output,0f);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inside get directory size");
    }

    /**
     * Test DirectorySize method when file don't exist then return length 0
     */
    @Test
    public void testDirectorySizeWhenFileExist() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("directorySize", String.class);
        method.setAccessible(true);
        tagFile = new File("file");
        FileWriter fw=new FileWriter(tagFile);
        fw.write("Token");
        fw.close();
        long output = (long) method.invoke(resourceConsumptionManager, "file");
        assertEquals(5f, output,0f);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inside get directory size");
    }

    /**
     * Test DirectorySize method when directory exist without file then return length 0
     */
    @Test
    public void testDirectorySizeWhenDirectoryExist() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("directorySize", String.class);
        method.setAccessible(true);
        dir = new File("dir");
        dir.mkdirs();
        long output = (long) method.invoke(resourceConsumptionManager, "dir");
        assertEquals(0f, output,0f);
    }

    /**
     * Test DirectorySize method when directory exist with empty file then return length 0
     */
    @Test
    public void testDirectorySizeWhenDirectoryWithEmptyFileExist() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("directorySize", String.class);
        method.setAccessible(true);
        dir = new File("emptyDir");
        dir.mkdirs();
        tagFile=new File("emptyDir","emptyFile.txt");
        tagFile.createNewFile();
        long output = (long) method.invoke(resourceConsumptionManager, "emptyDir");
        assertEquals(0f, output,0f);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inside get directory size");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished directory size : " + output);

    }

    /**
     * Test DirectorySize method when file exist then return length 0
     */
    @Test
    public void testDirectorySizeWhenDirectoryWithFileContentExist() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("directorySize", String.class);
        method.setAccessible(true);
        dir = new File("dir");
        dir.mkdirs();
        tagFile=new File("dir","newFile.txt");
        tagFile.createNewFile();
        FileWriter fw=new FileWriter(tagFile);
        fw.write("Token");
        fw.close();
        long output = (long) method.invoke(resourceConsumptionManager, "dir");
        assertEquals(5f, output,0f);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inside get directory size");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished directory size : " + output);
    }

    /**
     * Test removeArchives method
     */
    @Test
    public void testRemoveArchives() throws Exception{
        float amount = 0f;
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("removeArchives", float.class);
        method.setAccessible(true);
        method.invoke(resourceConsumptionManager, amount);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start remove archives : " + amount);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished remove archives : ");
        PowerMockito.verifyStatic(Configuration.class, Mockito.times(1));
        Configuration.getDiskDirectory();
    }

    /**
     * Test getMemoryUsage method
     */
    @Test
    public void testGetMemoryUsage() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getMemoryUsage");
        method.setAccessible(true);
        float memoryUsage = (float) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get memory usage");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get memory usage : " + (long)memoryUsage);
    }

    /**
     * Test getCpuUsage method
     */
    @Test
    public void testGetCpuUsage() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getCpuUsage");
        method.setAccessible(true);
        method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get cpu usage");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get cpu usage : 0");
    }

    /**
     * Test getSystemAvailableMemory method when executeCommand returns nothing
     */
    @Test
    public void testGetSystemAvailableMemoryWhenExecuteCommandReturnsNothing() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getSystemAvailableMemory");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        long output = (long) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get system available memory");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get system available memory : " + output);
    }

    /**
     * Test getSystemAvailableMemory method when executeCommand returns value 200
     */
    @Test
    public void testGetSystemAvailableMemoryWhenExecuteCommandReturnsValue() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getSystemAvailableMemory");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        value.add("200");
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        long output = (long) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get system available memory");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get system available memory : " + output);
    }

    /**
     * Test getSystemAvailableMemory method when executeCommand returns error
     */
    @Test
    public void testGetSystemAvailableMemoryWhenExecuteCommandReturnsError() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getSystemAvailableMemory");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        error.add("error");
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        long output = (long) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get system available memory");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get system available memory : " + output);
    }

    /**
     * Test getTotalCpu method when executeCommand returns nothing
     */
    @Test
    public void testGetTotalCpuWhenExecuteCommandReturnsNothing() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getTotalCpu");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        error.add("error");
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        float output = (float) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get total cpu");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get total cpu : " + output);
    }

    /**
     * Test getTotalCpu method when executeCommand returns value
     */
    @Test
    public void testGetTotalCpuWhenExecuteCommandReturnsValue() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getTotalCpu");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        value.add("5000");
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        float output = (float) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get total cpu");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get total cpu : " + output);
    }

    /**
     * Test getTotalCpu method when executeCommand returns error
     */
    @Test
    public void testGetTotalCpuWhenExecuteCommandReturnsError() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getTotalCpu");
        method.setAccessible(true);
        error = new ArrayList<>();
        value = new ArrayList<>();
        error.add("error");
        resultSetWithPath = new CommandShellResultSet<>(value, error);
        when(CommandShellExecutor.executeCommand(any())).thenReturn(resultSetWithPath);
        float output = (float) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get total cpu");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get total cpu : " + output);
    }

    /**
     * Test getAvailableDisk method
     */
    @Test
    public void testGetAvailableDisk() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("getAvailableDisk");
        method.setAccessible(true);
        long output = (long) method.invoke(resourceConsumptionManager);
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Start get available disk");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished get available disk : " + output);
    }

    /**
     * Test parseStat method when process Id is empty
     */
    @Test
    public void throwsFileNotFoundExceptionWhenParseStatIsPassedWithEmptyProcessId() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("parseStat", String.class);
        method.setAccessible(true);
        method.invoke(resourceConsumptionManager, "");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inisde parse Stat");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logError(any(), any(),  anyObject());
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished parse Stat");
    }

    /**
     * Test parseStat method when process Id is empty
     */
    @Test
    public void testParseStatWhenProcessIdIsPassed() throws Exception{
        Method method = ResourceConsumptionManager.class.getDeclaredMethod("parseStat", String.class);
        method.setAccessible(true);
        method.invoke(resourceConsumptionManager, "1111");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Inisde parse Stat");
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logError(any(), any(),  anyObject());
        PowerMockito.verifyStatic(LoggingService.class, Mockito.times(1));
        LoggingService.logInfo(MODULE_NAME,
                "Finished parse Stat");
    }

}