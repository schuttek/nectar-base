package org.nectarframework.base.service.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.nectarframework.base.exception.ConfigurationException;
import org.nectarframework.base.service.Service;
import org.nectarframework.base.service.ServiceUnavailableException;
import org.nectarframework.base.service.cache.CacheService;
import org.nectarframework.base.service.cache.CacheableObject;
import org.nectarframework.base.service.log.Log;

//TODO add cache layer

public class FileService extends Service {

	private String rootDirectory;
	private int readBufferSize;
	private int totalFileCacheSize;
	private int maxFilesInCache;
	private int maxCachedFileSize;

	private boolean recheckLastModified = true;

	private CacheService cacheService;

	private static final int maxReadBufferSize = 10485760; // 10MB
	private static final int maxTotalFileCacheSize = Integer.MAX_VALUE; // 2GB
	private static final int maxMaxFilesInCache = 50000;
	private static final int maxMaxCachedFileSize = Integer.MAX_VALUE; // 2GB

	private static final int defaultReadBufferSize = 65536; // 64KB
	private static final int defaultTotalFileCacheSize = 104857600; // 100MB
	private static final int defaultMaxFilesInCache = 1000;
	private static final int defaultMaxCachedFileSize = 134217728; // 128KB

	@Override
	protected boolean init() {
		return true;
	}

	@Override
	protected boolean run() {
		return true;
	}

	@Override
	protected boolean shutdown() {
		return true;
	}

	@Override
	public void checkParameters() throws ConfigurationException {
		String rootDir = this.serviceParameters.getValue("rootDirectory");
		if (rootDir != null) {
			File rootDirFile = new File(rootDir);
			if (!rootDirFile.exists()) {
				throw new ConfigurationException("FileService: rootDirectory couldn't be found.");
			}
			this.rootDirectory = rootDirFile.getAbsolutePath();
		}

		readBufferSize = serviceParameters.getInt("readBufferSize", -1, maxReadBufferSize, defaultReadBufferSize);
		totalFileCacheSize = serviceParameters.getInt("totalFileCacheSize", -1, maxTotalFileCacheSize,
				defaultTotalFileCacheSize);
		maxFilesInCache = serviceParameters.getInt("maxFilesInCache", -1, maxMaxFilesInCache, defaultMaxFilesInCache);
		maxCachedFileSize = serviceParameters.getInt("maxCachedFileSize", -1, maxMaxCachedFileSize,
				defaultMaxCachedFileSize);
		recheckLastModified = serviceParameters.getBoolean("recheckLastModified", true);
	}

	@Override
	public boolean establishDependancies() throws ServiceUnavailableException {
		cacheService = (CacheService) this.dependancy(CacheService.class);
		return true;
	}

	public long getFileContentLength(String filename) {
		File f = new File(rootDirectory + "/" + filename);
		long contentLength = f.length();
		return contentLength;
	}

	private void testFile(File f)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		if (!f.exists()) {
			Log.trace(f.getAbsolutePath());
			throw new ReadFileNotFoundException();
		}
		if (!f.canRead() && !f.isHidden()) {
			throw new ReadFileAccessDeniedException();
		}
		// check that we're not going above the root directory.
		if (!f.getAbsolutePath().startsWith(rootDirectory)) {
			throw new ReadFileAccessDeniedException();
		}
		if (!f.isFile()) {
			throw new ReadFileNotAFileException();
		}
	}

	private InputStream getFileAsInputStream(File f)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		testFile(f);
		Log.trace("reading file: " + f.getAbsolutePath());

		FileInputStream fis;
		try {
			fis = new FileInputStream(f);
		} catch (FileNotFoundException e) {
			throw new ReadFileNotFoundException();
		}
		return fis;
	}

	public InputStream getFileAsInputStream(String filename)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		File f = new File(rootDirectory + "/" + filename);
		return getFileAsInputStream(f);
	}

	public File getFile(String path)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		File f = new File(rootDirectory + "/" + path);
		testFile(f);
		return f;
	}

	public FileInfo getFileInfo(String path)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		return getFileInfo(path, -1);
	}

	public FileInfo getFileInfo(String path, long cacheExpiry)
			throws ReadFileNotFoundException, ReadFileAccessDeniedException, ReadFileNotAFileException {
		FileInfo fi = new FileInfo();

		File f = new File(rootDirectory + "/" + path);
		testFile(f);

		fi.path = path;
		fi.name = f.getName();
		fi.extension = "";

		int i = fi.name.lastIndexOf('.');
		if (i > 0) {
			fi.extension = fi.name.substring(i + 1);
		}
		fi.lastModified = f.lastModified();
		fi.length = f.length();

		return fi;
	}

	public byte[] readAllBytes(String path, long cacheExpiry)
			throws IOException {
		CacheableObject cachedCO;
		try {
			cachedCO = cacheService.getObject(cacheKey(path), true);
		} catch (Exception e) {
			Log.warn(e);
			return null;
		}
		
		FileInfo fi = null;
		// attempt a cache hit
		if (cachedCO != null) {
			fi = (FileInfo) cachedCO;
			if (this.recheckLastModified) {
				// cache is out of date
				if (fi.lastModified < getFileInfo(path).lastModified) {
					cacheService.remove(cacheKey(path));
					fi = null;
				}
			}
		}

		if (fi == null) {
			fi = getFileInfo(path, cacheExpiry);
			fi.contents = Files.readAllBytes(fi.getFile().toPath());
		}
		return fi.contents;
	}

	protected String cacheKey(String path) {
		return "FileService:" + path;
	}

}
