/*
 * *******************************************************************************
 *  * Copyright (c) 2018 Edgeworx, Inc.
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Eclipse Public License v. 2.0 which is available at
 *  * http://www.eclipse.org/legal/epl-2.0
 *  *
 *  * SPDX-License-Identifier: EPL-2.0
 *  *******************************************************************************
 *
 */

package org.eclipse.iofog.network;

import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.functional.Pair;
import org.eclipse.iofog.utils.logging.LoggingService;

import java.net.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;

import static org.eclipse.iofog.command_line.CommandLineConfigParam.NETWORK_INTERFACE;

/**
 * Created by ekrylovich
 * on 2/8/18.
 */
public class IOFogNetworkInterface {
	private static final String MODULE_NAME = "IOFogNetworkInterface";
    private static final String UNABLE_TO_GET_IP_ADDRESS = "Unable to get ip address. Please check network connection";

	/**
     * returns IPv4 host address of IOFog network interface
     *
     * @return {@link Inet4Address}
     * @throws Exception
     */
    public static String getCurrentIpAddress() {
        Optional<InetAddress> inetAddress = getLocalIp();
        return inetAddress.map(InetAddress::getHostAddress).orElse("");
    }

    private static Optional<InetAddress> getLocalIp() {
        Optional<InetAddress> inetAddress = Optional.empty();
        try {
            inetAddress = Optional.of(getInetAddress());
        } catch (SocketException exp) {
            LoggingService.logWarning(MODULE_NAME, "Unable to find the IP address of the machine running ioFog: " + exp.getMessage());
        }
        return inetAddress;
    }

    /**
     * returns IPv4 address of IOFog network interface
     *
     * @return {@link Inet4Address}
     * @throws Exception
     */
    public static InetAddress getInetAddress() throws SocketException {
        final Pair<NetworkInterface, InetAddress> connectedAddress = getNetworkInterface();
        if (connectedAddress != null) {
           return connectedAddress._2();
        }

        throw new ConnectException(UNABLE_TO_GET_IP_ADDRESS);
    }

    public static Pair<NetworkInterface, InetAddress> getNetworkInterface() {
        final String configNetworkInterface = Configuration.getNetworkInterface();
        if (NETWORK_INTERFACE.getDefaultValue().equals(configNetworkInterface)) {
            return getOSNetworkInterface();
        }

        try {
            URL controllerUrl = new URL(Configuration.getControllerUrl());
            NetworkInterface networkInterface = NetworkInterface.getByName(configNetworkInterface);
            return getConnectedAddress(controllerUrl, networkInterface);
        } catch (Exception e) {
            return null;
        }
    }

    private static Pair<NetworkInterface, InetAddress> getOSNetworkInterface() {
        try {
            URL controllerUrl = new URL(Configuration.getControllerUrl());

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface networkInterface: Collections.list(networkInterfaces)) {
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                    continue;
                }

                Pair<NetworkInterface, InetAddress> connectedAddress = getConnectedAddress(controllerUrl, networkInterface);
                if (connectedAddress != null) {
                    return connectedAddress;
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Pair<NetworkInterface, InetAddress> getConnectedAddress(URL controllerUrl, NetworkInterface networkInterface) {
        int controllerPort = controllerUrl.getPort();
        String controllerHost = controllerUrl.getHost();

        Enumeration<InetAddress> nifAddresses = networkInterface.getInetAddresses();
        for (InetAddress nifAddress: Collections.list(nifAddresses)) {
            if (!(nifAddress instanceof Inet4Address)) {
                continue;
            }

            try {
                Socket soc = new java.net.Socket();
                soc.bind(new InetSocketAddress(nifAddress, 0));
                soc.connect(new InetSocketAddress(controllerHost, controllerPort), 1000);
                soc.close();
                return Pair.of(networkInterface, nifAddress);
            } catch (Exception e) { }
        }

        return null;
    }
}