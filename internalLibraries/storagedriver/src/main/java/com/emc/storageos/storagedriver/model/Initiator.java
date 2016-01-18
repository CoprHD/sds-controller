package com.emc.storageos.storagedriver.model;

public class Initiator {


    /**
     * Initiator port identifier. For FC, this is the port WWN.
     * For iSCSI, this is port name in IQN or EUI format.
     * For IP port this is port IP address.
     */
    private String port;

    /**
     * For IP initiators this is initiator port network mask.
     */
    private String networkMask;

    /**
     * Initiator MAC address.
     */
    private String macAddress;

    /**
     * The initiator node identifier.
     */
    private String node;

    /**
     * Port communication protocol
     */
    private Protocol protocol;

    public static enum Protocol {
        FC,
        iSCSI,
        IPV4,
        IPV6,
        ScaleIO
    }

    /**
     * The FQDN of the initiator host.
     */
    private String hostName;

    /**
     * The FQDN of the cluster.
     */
    private String clusterName;

    // Label of this object. Type: input/output. If not supplied, should be set by driver.
    private String label;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public String getNetworkMask() {
        return networkMask;
    }

    public void setNetworkMask(String networkMask) {
        this.networkMask = networkMask;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }





}
