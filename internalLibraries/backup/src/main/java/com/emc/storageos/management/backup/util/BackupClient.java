/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.management.backup.util;

import com.emc.storageos.management.backup.ExternalServerType;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Interface used to do communication from vipr to external backup server
 */
public interface BackupClient {

    /**
     * upload file from to external server from offset
     * @param fileName upload filename
     * @param offset offset for upload begin
     * @return OutputStream of to be uploaded file on external server
     */
    public OutputStream upload(String fileName, long offset) throws Exception;

    /**
     *list backup file on external server
     * @param prefix prefix of file to be listed
     * @return list of files with prefix on external server
     */
    public List<String> listFiles(String prefix) throws Exception;

    /**
     *list backup files on external server
     * @return list of files on external server
     */
    public List<String> listAllFiles() throws Exception;

    /**
     * Download file from to external server to vipr
     * @param BackupFileName download filename
     * @return InputStream of to be downloaded file on external server
     */
    public InputStream download(String BackupFileName) throws Exception;

    /**
     * Rename the filename on external server
     * @param sourceFileName source filename
     * @param destFileName rename filename
     */
    public void rename(String sourceFileName, String destFileName) throws Exception;

    /**
     * get the file size on external server
     * @param fileName  filename
     * @return file size
     */
    public long getFileSize(String fileName) throws Exception;

    /**
     * get the uri of external server
     * @return external server URI
     */
    public String getUri();

}
