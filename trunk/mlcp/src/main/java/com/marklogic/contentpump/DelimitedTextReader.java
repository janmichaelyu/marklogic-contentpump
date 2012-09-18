/*
 * Copyright 2003-2012 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.marklogic.contentpump;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

/**
 * Reader for DelimitedTextInputFormat.
 * @author ali
 *
 * @param <VALUEIN>
 */
public class DelimitedTextReader<VALUEIN> extends
    ImportRecordReader<VALUEIN> {
    public static final Log LOG = LogFactory.getLog(DelimitedTextReader.class);
    protected String[] fields;
    protected String delimiter;
    protected static String ROOT_START = "<root>";
    protected static String ROOT_END = "</root>";
    protected BufferedReader br;
    protected boolean hasNext = true;
    protected String idName;
    protected long fileLen = Long.MAX_VALUE;
    protected long bytesRead;
    
    @Override
    public void close() throws IOException {
        if (br != null) {
            br.close();
        }
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
        return bytesRead/fileLen;
    }

    @Override
    public void initialize(InputSplit inSplit, TaskAttemptContext context)
        throws IOException, InterruptedException {
        initConfig(context);
        
        Path file = ((FileSplit) inSplit).getPath();
        configFileNameAsCollection(conf, file);
        FileSystem fs = file.getFileSystem(context.getConfiguration());
        FSDataInputStream fileIn = fs.open(file);
        if (encoding == null) {
            br = new BufferedReader(new InputStreamReader(fileIn));
        } else {
            br = new BufferedReader(new InputStreamReader(fileIn, encoding));
            //String will be converted and read as UTF-8 String
        }
        fileLen = inSplit.getLength();
        initDelimConf(conf);
    }

    protected void initDelimConf(Configuration conf) {
        delimiter = conf.get(ConfigConstants.CONF_DELIMITER,
                ConfigConstants.DEFAULT_DELIMITER);
        if (delimiter.length() == 1) {
            delimiter = "\\" + delimiter;
        } else {
            LOG.error("Incorrect delimitor: " + delimiter);
        }
        idName = conf.get(ConfigConstants.CONF_DELIMITED_URI_ID, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
        if (br == null) {
            return false;
        }
        String line = br.readLine();
        
        // skip empty lines
        while (line != null) {
            bytesRead += line.getBytes().length;
            if (!"".equals(line.trim())) {
                break;
            }
            line = br.readLine();
        }
        if (line == null) {
            bytesRead = fileLen;
            return false;
        }
        if (fields == null) {
            fields = line.split(delimiter);
            boolean found = false;
            for (int i = 0; i < fields.length; i++) {
                // Oracle jdk bug 4508058: UTF-8 encoding does not recognize
                // initial BOM
                // will not be fixed. Work Around :
                // Application code must recognize and skip the BOM itself.
                byte[] buf = fields[i].getBytes();
                if (buf[0] == (byte) 0xEF && buf[1] == (byte) 0xBB
                    && buf[2] == (byte) 0xBF) {
                    fields[i] = new String(buf, 3, buf.length - 3);
                }
                if (i == 0 && idName == null
                    || fields[i].trim().equals(idName)) {
                    idName = fields[i].trim();
                    found = true;
                    break;
                }
            }
            if (found == false) {
                // idname doesn't match any columns
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Header: " + line);
                }
                throw new IOException(
                    "Delimited_uri_id " + idName + " is not found.");
            }
            line = br.readLine();
            
            // skip empty lines
            while (line != null) {
                bytesRead += line.getBytes().length;
                if (!"".equals(line.trim())) {
                    break;
                }
                line = br.readLine();
            }
            if (line == null) {
                bytesRead = fileLen;
                return false;
            } 
        }

        String[] values = line.split(delimiter);
        if (values.length != fields.length) {
            LOG.error(line + " is inconsistent with column definition");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ROOT_START);
        for (int i = 0; i < fields.length; i++) {
            if (idName.equals(fields[i])) {
                if (values[i] == null || values[i].trim().equals("")) {
                    LOG.error(line + ":column used for uri_id is empty");
                    //clear the key of previous record 
                    key = null;
                    return true;
                }
                String uri = getEncodedURI(values[i].trim());
                if (uri != null) {
                    setKey(uri);
                } else {
                    key = null;
                    return true;
                }
            }
            sb.append("<").append(fields[i]).append(">");
            sb.append(values[i]);
            sb.append("</").append(fields[i]).append(">");
        }
        sb.append(ROOT_END);
        if (value instanceof Text) {
            ((Text) value).set(sb.toString());
        } else if (value instanceof ContentWithFileNameWritable) {
            VALUEIN realValue = ((ContentWithFileNameWritable<VALUEIN>) value)
                .getValue();
            if (realValue instanceof Text) {
                ((Text) realValue).set(sb.toString());
            } else {
                LOG.error("Expects Text in delimited text");
                key = null;
            }
        } else {
            LOG.error("Expects Text in delimited text");
            key = null;
        }
        return true;
    }
}
