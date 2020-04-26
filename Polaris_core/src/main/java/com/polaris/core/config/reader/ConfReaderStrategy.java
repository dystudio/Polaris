package com.polaris.core.config.reader;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;

public interface ConfReaderStrategy {
	String getContents (String fileName);
	Properties getProperties (String fileName);
	InputStream getInputStream (String fileName);
	File getFile (String fileName);
}
