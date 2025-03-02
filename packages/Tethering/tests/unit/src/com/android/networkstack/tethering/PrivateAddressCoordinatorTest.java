/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.util.PrefixUtils.asIpPrefix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ip.IpServer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class PrivateAddressCoordinatorTest {
    private static final String TEST_IFNAME = "test0";

    @Mock private IpServer mHotspotIpServer;
    @Mock private IpServer mUsbIpServer;
    @Mock private IpServer mEthernetIpServer;
    @Mock private IpServer mWifiP2pIpServer;
    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityMgr;
    @Mock private TetheringConfiguration mConfig;

    private PrivateAddressCoordinator mPrivateAddressCoordinator;
    private final LinkAddress mBluetoothAddress = new LinkAddress("192.168.44.1/24");
    private final LinkAddress mLegacyWifiP2pAddress = new LinkAddress("192.168.49.1/24");
    private final Network mWifiNetwork = new Network(1);
    private final Network mMobileNetwork = new Network(2);
    private final Network mVpnNetwork = new Network(3);
    private final Network[] mAllNetworks = {mMobileNetwork, mWifiNetwork, mVpnNetwork};

    private void setUpIpServers() throws Exception {
        when(mUsbIpServer.interfaceType()).thenReturn(TETHERING_USB);
        when(mEthernetIpServer.interfaceType()).thenReturn(TETHERING_ETHERNET);
        when(mHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mWifiP2pIpServer.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mConnectivityMgr);
        when(mConnectivityMgr.getAllNetworks()).thenReturn(mAllNetworks);
        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(false);
        setUpIpServers();
        mPrivateAddressCoordinator = spy(new PrivateAddressCoordinator(mContext, mConfig));
    }

    @Test
    public void testRequestDownstreamAddressWithoutUsingLastAddress() throws Exception {
        final IpPrefix bluetoothPrefix = asIpPrefix(mBluetoothAddress);
        final LinkAddress address = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        assertNotEquals(hotspotPrefix, bluetoothPrefix);
        when(mHotspotIpServer.getAddress()).thenReturn(address);

        final LinkAddress newAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        final IpPrefix testDupRequest = asIpPrefix(newAddress);
        assertNotEquals(hotspotPrefix, testDupRequest);
        assertNotEquals(bluetoothPrefix, testDupRequest);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        final LinkAddress usbAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mUsbIpServer, false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(usbPrefix, bluetoothPrefix);
        assertNotEquals(usbPrefix, hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);
    }

    @Test
    public void testSanitizedAddress() throws Exception {
        int fakeSubAddr = 0x2b00; // 43.0.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeSubAddr);
        LinkAddress actualAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.43.42/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2d01; // 45.1.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeSubAddr);
        actualAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.45.42/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2eff; // 46.255.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeSubAddr);
        actualAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.46.42/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        fakeSubAddr = 0x2f05; // 47.5.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeSubAddr);
        actualAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        assertEquals(new LinkAddress("192.168.47.5/24"), actualAddress);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testReservedPrefix() throws Exception {
        // - Test bluetooth prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(
                getSubAddress(mBluetoothAddress.getAddress().getAddress()));
        final LinkAddress hotspotAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        // - Test previous enabled hotspot prefix(cached prefix) is reserved.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(
                getSubAddress(hotspotAddress.getAddress().getAddress()));
        final LinkAddress usbAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mUsbIpServer, false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), usbPrefix);
        assertNotEquals(hotspotPrefix, usbPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        // - Test wifi p2p prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        final LinkAddress etherAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mEthernetIpServer, false /* useLastAddress */);
        final IpPrefix etherPrefix = asIpPrefix(etherAddress);
        assertNotEquals(asIpPrefix(mLegacyWifiP2pAddress), etherPrefix);
        assertNotEquals(asIpPrefix(mBluetoothAddress), etherPrefix);
        assertNotEquals(hotspotPrefix, etherPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mEthernetIpServer);
    }

    @Test
    public void testRequestLastDownstreamAddress() throws Exception {
        final int fakeHotspotSubAddr = 0x2b05;
        final IpPrefix predefinedPrefix = new IpPrefix("192.168.43.0/24");
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeHotspotSubAddr);
        final LinkAddress hotspotAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, true /* useLastAddress */);
        assertEquals("Wrong wifi prefix: ", predefinedPrefix, asIpPrefix(hotspotAddress));
        when(mHotspotIpServer.getAddress()).thenReturn(hotspotAddress);

        final LinkAddress usbAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mUsbIpServer, true /* useLastAddress */);
        assertNotEquals(predefinedPrefix, asIpPrefix(usbAddress));

        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        final int newFakeSubAddr = 0x3c05;
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeHotspotSubAddr);

        final LinkAddress newHotspotAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, true /* useLastAddress */);
        assertEquals(hotspotAddress, newHotspotAddress);
        final LinkAddress newUsbAddress = mPrivateAddressCoordinator.requestDownstreamAddress(
                mUsbIpServer, true /* useLastAddress */);
        assertEquals(usbAddress, newUsbAddress);
    }

    private UpstreamNetworkState buildUpstreamNetworkState(final Network network,
            final LinkAddress v4Addr, final LinkAddress v6Addr, final NetworkCapabilities cap) {
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFNAME);
        if (v4Addr != null) prop.addLinkAddress(v4Addr);

        if (v6Addr != null) prop.addLinkAddress(v6Addr);

        return new UpstreamNetworkState(prop, cap, network);
    }

    private NetworkCapabilities makeNetworkCapabilities(final int transportType) {
        final NetworkCapabilities cap = new NetworkCapabilities();
        cap.addTransportType(transportType);
        if (transportType == TRANSPORT_VPN) {
            cap.removeCapability(NET_CAPABILITY_NOT_VPN);
        }

        return cap;
    }

    @Test
    public void testNoConflictUpstreamPrefix() throws Exception {
        final int fakeHotspotSubId = 43;
        final int fakeHotspotSubAddr = 0x2b05;
        final IpPrefix predefinedPrefix = new IpPrefix("192.168.43.0/24");
        // Force always get subAddress "43.5" for conflict testing.
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(fakeHotspotSubAddr);
        // - Enable hotspot with prefix 192.168.43.0/24
        final LinkAddress hotspotAddr = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, true /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddr);
        assertEquals("Wrong wifi prefix: ", predefinedPrefix, hotspotPrefix);
        when(mHotspotIpServer.getAddress()).thenReturn(hotspotAddr);
        // - test mobile network with null NetworkCapabilities. Ideally this should not happen
        // because NetworkCapabilities update should always happen before LinkProperties update
        // and the UpstreamNetworkState update, just make sure no crash in this case.
        final UpstreamNetworkState noCapUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), null, null);
        mPrivateAddressCoordinator.updateUpstreamPrefix(noCapUpstream);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - test mobile upstream with no address.
        final UpstreamNetworkState noAddress = buildUpstreamNetworkState(mMobileNetwork,
                null, null, makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(noCapUpstream);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v6 only mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v6OnlyMobile = buildUpstreamNetworkState(mMobileNetwork,
                null, new LinkAddress("2001:db8::/64"),
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v6OnlyMobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.removeUpstreamPrefix(mMobileNetwork);
        // - Update v4 only mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v4OnlyMobile = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyMobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v4v6 mobile network, hotspot prefix should not be removed.
        final UpstreamNetworkState v4v6Mobile = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("10.0.0.8/24"), new LinkAddress("2001:db8::/64"),
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4v6Mobile);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v6 only wifi network, hotspot prefix should not be removed.
        final UpstreamNetworkState v6OnlyWifi = buildUpstreamNetworkState(mWifiNetwork,
                null, new LinkAddress("2001:db8::/64"), makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v6OnlyWifi);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.removeUpstreamPrefix(mWifiNetwork);
        // - Update vpn network, it conflict with hotspot prefix but VPN networks are ignored.
        final UpstreamNetworkState v4OnlyVpn = buildUpstreamNetworkState(mVpnNetwork,
                new LinkAddress("192.168.43.5/24"), null, makeNetworkCapabilities(TRANSPORT_VPN));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyVpn);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Update v4 only wifi network, it conflict with hotspot prefix.
        final UpstreamNetworkState v4OnlyWifi = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.43.5/24"), null, makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyWifi);
        verify(mHotspotIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        reset(mHotspotIpServer);
        // - Restart hotspot again and its prefix is different previous.
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        final LinkAddress hotspotAddr2 = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, true /* useLastAddress */);
        final IpPrefix hotspotPrefix2 = asIpPrefix(hotspotAddr2);
        assertNotEquals(hotspotPrefix, hotspotPrefix2);
        when(mHotspotIpServer.getAddress()).thenReturn(hotspotAddr2);
        mPrivateAddressCoordinator.updateUpstreamPrefix(v4OnlyWifi);
        verify(mHotspotIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        // - Usb tethering can be enabled and its prefix is different with conflict one.
        final LinkAddress usbAddr = mPrivateAddressCoordinator.requestDownstreamAddress(
                mUsbIpServer, true /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddr);
        assertNotEquals(predefinedPrefix, usbPrefix);
        assertNotEquals(hotspotPrefix2, usbPrefix);
        when(mUsbIpServer.getAddress()).thenReturn(usbAddr);
        // - Disable wifi upstream, then wifi's prefix can be selected again.
        mPrivateAddressCoordinator.removeUpstreamPrefix(mWifiNetwork);
        final LinkAddress ethAddr = mPrivateAddressCoordinator.requestDownstreamAddress(
                mEthernetIpServer, true /* useLastAddress */);
        final IpPrefix ethPrefix = asIpPrefix(ethAddr);
        assertEquals(predefinedPrefix, ethPrefix);
    }

    private int getSubAddress(final byte... ipv4Address) {
        assertEquals(4, ipv4Address.length);

        int subnet = Byte.toUnsignedInt(ipv4Address[2]);
        return (subnet << 8) + ipv4Address[3];
    }

    private void assertReseveredWifiP2pPrefix() throws Exception {
        LinkAddress address = mPrivateAddressCoordinator.requestDownstreamAddress(
                mHotspotIpServer, true /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        final IpPrefix legacyWifiP2pPrefix = asIpPrefix(mLegacyWifiP2pAddress);
        assertNotEquals(legacyWifiP2pPrefix, hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testEnableLegacyWifiP2PAddress() throws Exception {
        when(mPrivateAddressCoordinator.getRandomSubAddr()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        // No matter #shouldEnableWifiP2pDedicatedIp() is enabled or not, legacy wifi p2p prefix
        // is resevered.
        assertReseveredWifiP2pPrefix();

        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(true);
        assertReseveredWifiP2pPrefix();

        // If #shouldEnableWifiP2pDedicatedIp() is enabled, wifi P2P gets the configured address.
        LinkAddress address = mPrivateAddressCoordinator.requestDownstreamAddress(
                mWifiP2pIpServer, true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);
    }
}
