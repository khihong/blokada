/*
 * This file is part of Blokada.
 *
 * Blokada is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Blokada is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Blokada.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright © 2020 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package engine

import com.cloudflare.app.boringtun.BoringTunJNI
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.*
import newengine.BlockaDnsService
import repository.DnsDataSource
import service.ConnectivityService
import service.EnvironmentService
import utils.Logger
import java.net.DatagramSocket
import java.net.Socket

object EngineService {

    private val log = Logger("Engine")
    private val systemTunnel = SystemTunnelService
    private val packetLoop = PacketLoopService
    private val filtering = FilteringService
    private val dnsMapper = DnsMapperService
    private val dnsService = BlockaDnsService
    private val connectivity = ConnectivityService
    private val configurator = SystemTunnelConfigurator
    private val scope = GlobalScope

    var onTunnelStoppedUnexpectedly = { ex: BlokadaException -> }

    private lateinit var config: EngineConfiguration
        @Synchronized set
        @Synchronized get

    private val state = EngineState()

    fun setup(network: NetworkSpecificConfig, user: BlockaConfig) {
        log.v("Engine initializing")
        JniService.setup()

        packetLoop.onCreateSocket = {
            val socket = DatagramSocket()
            systemTunnel.protectSocket(socket)
            socket
        }

        packetLoop.onStoppedUnexpectedly = {
            scope.launch {
                systemTunnel.close()
                state.stopped()
                onTunnelStoppedUnexpectedly(BlokadaException("PacketLoop stopped"))
            }
        }

        systemTunnel.onTunnelClosed = { ex: BlokadaException? ->
            ex?.let {
                scope.launch {
                    if (!state.isRestarting()) {
                        packetLoop.stop()
                        state.stopped()
                        onTunnelStoppedUnexpectedly(it)
                    }
                }
            }
        }

        config = EngineConfiguration.new(network, user)
    }

    suspend fun updateConfig(network: NetworkSpecificConfig? = null, user: BlockaConfig? = null) {
        log.w("Updating engine config")

        network?.let {
            config = config.newNetworkConfig(network)
            reload(config)
        }

        user?.let {
            config = config.newUserConfig(user)
            reload(config)
        }
    }

    private suspend fun reload(config: EngineConfiguration, force: Boolean = false) {
        log.v("Reloading engine, config: $config")

        when {
            state.isInProgress() -> {
                log.w("Engine currently reloading, ignoring")
                return
            }
            !force && config == state.currentConfig -> {
                log.w("Configuration change does not require engine reload, ignoring")
                return
            }
        }

        val wasActive = state.tunnel.active
        val onlyReloadPacketLoop = state.canReloadJustPacketLoop(config)

        state.restarting()

        if (wasActive) {
            if (onlyReloadPacketLoop) {
                packetLoop.stop()
            } else {
                packetLoop.stop()
                dnsService.stopDnsProxy()
                systemTunnel.close()
                log.w("Waiting after stopping system tunnel, before another start")
                delay(5000)
            }
        }

        when {
            !config.tunnelEnabled -> {
                state.stopped(config)
            }
//            onlyReloadPacketLoop -> {
//                packetLoop.startPlusMode(
//                    useDoh = config.,
//                    dns = dns,
//                    tunnelConfig = systemTunnel.getTunnelConfig(),
//                    privateKey = config.privateKey,
//                    gateway = config.gateway()
//                )
//                status = TunnelStatus.connected(dns, doh, config.gateway())
//            }
            else -> startAll(config)
        }

        if (this.config != config) {
            log.v("Another reload was queued, executing")
            reload(this.config)
        }
    }

    private suspend fun startAll(config: EngineConfiguration) {
        state.inProgress()
        config.run {
            when {
                // Plus mode
                isPlusMode() -> {
                    dnsMapper.setDns(dns, doh, plusMode = true)
                    if (doh) dnsService.startDnsProxy(dns)
                    systemTunnel.onConfigureTunnel = { tun ->
                        configurator.forPlus(tun, dns, lease = config.lease())
                    }
                    systemTunnel.open()
                    packetLoop.startPlusMode(
                        useDoh = doh,
                        dns = dns,
                        tunnelConfig = systemTunnel.getTunnelConfig(),
                        privateKey = config.privateKey,
                        gateway = config.gateway()
                    )
                    state.plusMode(config)
                }
                // Slim mode
                EnvironmentService.isSlim() -> {
                    dnsMapper.setDns(dns, doh)
                    if (doh) dnsService.startDnsProxy(dns)
                    systemTunnel.onConfigureTunnel = { tun ->
                        configurator.forLibre(tun, dns)
                    }
                    val tunnelConfig = systemTunnel.open()
                    packetLoop.startSlimMode(doh, dns, tunnelConfig)
                    state.libreMode(config)
                }
                // Libre mode
                else -> {
                    dnsMapper.setDns(dns, doh)
                    if (doh) dnsService.startDnsProxy(dns)
                    systemTunnel.onConfigureTunnel = { tun ->
                        configurator.forLibre(tun, dns)
                    }
                    val tunnelConfig = systemTunnel.open()
                    packetLoop.startLibreMode(doh, dns, tunnelConfig)
                    state.libreMode(config)
                }
            }
        }
    }

    suspend fun reloadBlockLists() {
        filtering.reload()
        reload(config, force = true)
    }

    suspend fun forceReload() {
        reload(config, force = true)
    }

    fun getTunnelStatus(): TunnelStatus {
        return state.tunnel
    }

    fun setOnTunnelStatusChangedListener(onTunnelStatusChanged: (TunnelStatus) -> Unit) {
        state.onTunnelStatusChanged = onTunnelStatusChanged
    }

    fun goToBackground() {
        systemTunnel.unbind()
    }

    fun newKeypair(): Pair<PrivateKey, PublicKey> {
        val secret = BoringTunJNI.x25519_secret_key()
        val public = BoringTunJNI.x25519_public_key(secret)
        val secretString = BoringTunJNI.x25519_key_to_base64(secret)
        val publicString = BoringTunJNI.x25519_key_to_base64(public)
        return secretString to publicString
    }

    fun protectSocket(socket: Socket) {
        systemTunnel.protectSocket(socket)
    }


}

private data class EngineConfiguration(
    val tunnelEnabled: Boolean,
    val dns: Dns,
    val doh: Boolean,
    val privateKey: PrivateKey,
    val gateway: Gateway?,
    val lease: Lease?,

    val network: NetworkSpecificConfig,
    val user: BlockaConfig
) {

    fun isPlusMode() = gateway != null
    fun lease() = lease!!
    fun gateway() = gateway!!

    fun newUserConfig(user: BlockaConfig) = new(network, user)
    fun newNetworkConfig(network: NetworkSpecificConfig) = new(network, user)

    companion object {
        fun new(network: NetworkSpecificConfig, user: BlockaConfig): EngineConfiguration {
            val (dnsForLibre, dnsForPlus) = decideDnsForNetwork(network)
            val plusMode = decidePlusMode(dnsForPlus, user)
            val dns = if (plusMode) dnsForPlus else dnsForLibre

            return EngineConfiguration(
                tunnelEnabled = user.tunnelEnabled,
                dns = dns,
                doh = decideDoh(dns, plusMode, network.encryptDns),
                privateKey = user.privateKey,
                gateway = if (plusMode) user.gateway else null,
                lease = if (plusMode) user.lease else null,
                network = network,
                user = user
            )
        }

        private fun decideDnsForNetwork(n: NetworkSpecificConfig): Pair<Dns, Dns> {
            /**
             * The DNS server choice gets a bit complicated:
             * - useNetworkDns will force to use network-provided DNS servers (if any) and to not use DoH
             *   despite user setting (in useDoh()). If no network DNS servers were detected, then it'll
             *   use the cfg.dnsChoice after all.
             * - For Plus Mode, ignore the useNetworkDns setting, since network DNS would not resolve
             *   under the real VPN. Instead, use the useBlockaDnsInPlusMode flag (which is true by
             *   default), to safely fallback to our DNS.
             *
             * This approach may not address all user network specific problems, but I could not think
             * of a better one.
             */

            // Here we assume the network we work with is the currently active network
            val forLibre = if (n.useNetworkDns && ConnectivityService.getActiveNetworkDns().isNotEmpty()) {
                DnsDataSource.network
            } else {
                DnsDataSource.byId(n.dnsChoice)
            }

            val forPlus = if (n.useBlockaDnsInPlusMode) DnsDataSource.blocka else forLibre

            return forLibre to forPlus
        }

        private fun decidePlusMode(dns: Dns, user: BlockaConfig) = when {
            !user.tunnelEnabled -> false
            !user.vpnEnabled -> false
            user.lease == null -> false
            user.gateway == null -> false
            dns == DnsDataSource.network -> {
                // Network provided DNS are likely not accessibly within the VPN.
                false
            }
            else -> true
        }

        private fun decideDoh(dns: Dns, plusMode: Boolean, encryptDns: Boolean) = when {
            dns.id == DnsDataSource.network.id -> {
                // Only plaintext network DNS are supported currently
                false
            }
            plusMode && dns.plusIps != null -> {
                // If plusIps are set, they will point to a clear text DNS, because the plus mode
                // VPN itself is encrypting everything, so there is no need to encrypt DNS.
                Logger.w("Engine", "Using clear text as DNS defines special IPs for plusMode")
                false
            }
            dns.isDnsOverHttps() && !dns.canUseInCleartext -> {
                // If DNS supports only DoH and no clear text, we are forced to use it
                Logger.w("Engine", "Forcing DoH as selected DNS does not support clear text")
                true
            }
            else -> {
                dns.isDnsOverHttps() && encryptDns
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EngineConfiguration

        if (tunnelEnabled != other.tunnelEnabled) return false
        if (dns != other.dns) return false
        if (doh != other.doh) return false
        if (privateKey != other.privateKey) return false
        if (gateway != other.gateway) return false
        if (lease != other.lease) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tunnelEnabled.hashCode()
        result = 31 * result + dns.hashCode()
        result = 31 * result + doh.hashCode()
        result = 31 * result + privateKey.hashCode()
        result = 31 * result + (gateway?.hashCode() ?: 0)
        result = 31 * result + (lease?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "(enabled=$tunnelEnabled, dns=${dns.id}, doh=$doh, gw=${gateway?.niceName()})"
    }


}

private data class EngineState(
    var tunnel: TunnelStatus = TunnelStatus.off(),
    var currentConfig: EngineConfiguration? = null,
    var onTunnelStatusChanged: (TunnelStatus) -> Unit = { _ -> }
) {

    @Synchronized fun inProgress() {
        tunnel = TunnelStatus.inProgress()
        onTunnelStatusChanged(tunnel)
    }

    @Synchronized fun restarting() {
        tunnel = TunnelStatus.restarting()
        onTunnelStatusChanged(tunnel)
    }

    @Synchronized fun libreMode(config: EngineConfiguration) {
        tunnel = TunnelStatus.filteringOnly(config.dns, config.doh, config.gateway?.public_key)
        currentConfig = config
        onTunnelStatusChanged(tunnel)
    }

    @Synchronized fun plusMode(config: EngineConfiguration) {
        tunnel = TunnelStatus.connected(config.dns, config.doh, config.gateway())
        currentConfig = config
        onTunnelStatusChanged(tunnel)
    }

    @Synchronized fun stopped(config: EngineConfiguration? = null) {
        tunnel = TunnelStatus.off()
        currentConfig = config
        onTunnelStatusChanged(tunnel)
    }

    @Synchronized fun isRestarting() = tunnel.restarting
    @Synchronized fun isInProgress() = tunnel.inProgress

    @Synchronized fun canReloadJustPacketLoop(config: EngineConfiguration) = when {
        // TODO
        else -> false
    }

}