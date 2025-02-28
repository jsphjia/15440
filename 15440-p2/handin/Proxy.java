/**
 * File: Proxy.java
 * Description: Implements a file-caching proxy
 * Author: Joseph Jia (josephji)
 * 
 * This file implements a file-caching proxy that acts as an intermidiate
 * between the client and server. The proxy uses Java RMI to communicate
 * with the server.
 */

// Imported Libraries
import java.io.*;
import java.nio.file.OpenOption;
import java.nio.file.*;
import java.util.*;
import java.rmi.*;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.util.concurrent.ConcurrentHashMap;

class Proxy {
	// Global Variables
	public static ConcurrentHashMap<Integer, RandomAccessFile> fd_files;
	public static ConcurrentHashMap<Integer, String> fd_paths;
	public static int curr_fd;
	public static String hostIP;
	public static int port;
	public static RMIInterface stub;

	// Cache Variables
	public static Cache cache;
	public static int max_size;
	public static String cache_dir;
	public static Object cache_lock = new Object();

	// Additional Constant Values
	public static final int EIO = -5;
	public static final int CHUNK_SIZE = 50000;

	private static class FileHandler implements FileHandling {
		/*
		 * Function: getFd
		 * This function determines the file descriptor for the next file.
		 * Uses synchronization to ensure unique file descriptors.
		 * 
		 * @return file descriptor value
		 */
		public synchronized int getFd() {
			int fd = curr_fd;
			curr_fd ++;
			return fd;
		}

		/*
		 * Function: open
		 * This function implements the file open function.
		 * It calls the server to create a file and stores it in the cache.
		 * 
		 * @param path - file path to open
		 * @param o - mode to open file with
		 * @return file descriptor on success; error value on failure
		 */
		public int open (String path, OpenOption o) {
			int fd, buf_size, max_ver;
			long length, curr_pos;
			File file, fd_file;
			String fd_path, cache_path, min_path, dir_path;
			RandomAccessFile raf, fd_raf, tmp_raf;
			byte[] buf;

			min_path = Path.of(path).normalize().toString();
			dir_path = Path.of(cache_dir + "/" + path).normalize().toString();

			// make sure path is in working directory
			if (!dir_path.startsWith(cache_dir)) {
				return Errors.EINVAL;
			}

			// get max version
			try {
				max_ver = stub.serverExists(min_path);
			} catch (RemoteException e) {
				System.err.println(e.toString());
				return EIO;
			}
			if (max_ver != 0) {
				cache_path = dir_path + "-" + max_ver;
			}
			else {
				cache_path = dir_path;
			}

			switch (o) {
				case CREATE:
					try {
						synchronized (cache_lock) {
							// check if file exists on the cache already
							if (cache.containsKey(cache_path)) {
								file = cache.get(cache_path);
								tmp_raf = new RandomAccessFile(file, "rw");
							}
							else {
								// check if file exists on server
								if (max_ver == 0) {
									stub.createFile(min_path);
									max_ver++;
								}

								// check if file exists on proxy
								cache_path = dir_path + "-" + max_ver;
								file = new File(cache_path);
								if (!file.exists()) {
									// create parent directories if they exist
									if (file.getParentFile() != null) {
										file.getParentFile().mkdirs();
									}
									file.createNewFile();
								}

								// copy over contents from server copy to proxy copy
								length = stub.getFileLength(min_path);
								curr_pos = 0;
								tmp_raf = new RandomAccessFile(file, "rw");
								while (curr_pos < length) {
									buf = stub.getFileInfo(min_path, curr_pos);
									tmp_raf.seek(curr_pos);
									tmp_raf.write(buf);
									curr_pos += buf.length;
								}
								cache.put(cache_path, file);
							}
						}

						// create fd copy
						fd = getFd();
						min_path = min_path + "-" + max_ver;
						fd_path = cache_dir + "/" + min_path + "-w" + fd;
						System.err.println(fd_path);
						fd_file = new File(fd_path);
						if (fd_file.getParentFile() != null) {
							fd_file.getParentFile().mkdirs();
						}
						fd_file.createNewFile();
						fd_file.setReadable(true, false);
						fd_file.setWritable(true, false);

						// copy over contents from proxy copy to fd copy
						fd_raf = new RandomAccessFile(fd_file, "rw");
						length = tmp_raf.length();
						curr_pos = 0;
						tmp_raf.seek(0);
						while (curr_pos < length) {
							if (length - curr_pos > CHUNK_SIZE) {
								buf_size = CHUNK_SIZE;
							}
							else {
								buf_size = (int)(length - curr_pos);
							}
							buf = new byte[buf_size];
							tmp_raf.seek(curr_pos);
							tmp_raf.read(buf);
							fd_raf.seek(curr_pos);
							fd_raf.write(buf);
							curr_pos += buf_size;
						}
						fd_raf.seek(0);

						// update all hash maps
						fd_paths.put(fd, min_path);
						fd_files.put(fd, fd_raf);
						cache.put(fd_path, fd_file);
						cache.addClient(fd_path);
						return fd;
					} catch (IOException e) {
						System.err.println("open (create): " + e.toString());
						return EIO;
					}
				case CREATE_NEW:
					try {
						// check if file exists on the cache already
						synchronized (cache_lock) {
							if (cache.containsKey(cache_path)) {
								return Errors.EEXIST;
							}
							// file doesn't exist in the cache
							else {
								// check if file exists on server
								if (max_ver != 0) {
									return Errors.EEXIST;
								}

								// check if file exists on proxy
								cache_path = dir_path + "-1";
								file = new File(cache_path);
								if (file.exists()) {
									return Errors.EEXIST;
								}
								stub.createFile(path);
								max_ver++;
								// create parent directories if they exist
								if (file.getParentFile() != null) {
									file.getParentFile().mkdirs();
								}
								file.createNewFile();

								// copy over contents from server copy to proxy copy
								length = stub.getFileLength(path);
								curr_pos = 0;
								tmp_raf = new RandomAccessFile(file, "rw");
								while (curr_pos < length) {
									buf = stub.getFileInfo(path, curr_pos);
									tmp_raf.seek(curr_pos);
									tmp_raf.write(buf);
									curr_pos += buf.length;
								}

								cache.put(cache_path, file);
							}
						}

						// create fd copy
						fd = getFd();
						min_path = min_path + "-" + max_ver;
						fd_path = cache_dir + "/" + min_path + "-w" + fd;
						fd_file = new File(fd_path);
						if (fd_file.getParentFile() != null) {
							fd_file.getParentFile().mkdirs();
						}
						fd_file.createNewFile();
						fd_file.setReadable(true, false);
						fd_file.setWritable(true, false);

						// copy over contents from proxy copy to fd copy
						fd_raf = new RandomAccessFile(fd_file, "rw");
						length = tmp_raf.length();
						curr_pos = 0;
						tmp_raf.seek(0);
						while (curr_pos < length) {
							if (length - curr_pos > CHUNK_SIZE) {
								buf_size = CHUNK_SIZE;
							}
							else {
								buf_size = (int)(length - curr_pos);
							}
							buf = new byte[buf_size];
							tmp_raf.seek(curr_pos);
							tmp_raf.read(buf);
							fd_raf.seek(curr_pos);
							fd_raf.write(buf);
							curr_pos += buf_size;
						}
						fd_raf.seek(0);

						// update all hash maps
						fd_paths.put(fd, min_path);
						fd_files.put(fd, fd_raf);
						cache.put(fd_path, fd_file);
						cache.addClient(fd_path);
						return fd;
					} catch (IOException e) {
						System.err.println("open (create_new): " + e.toString());
						return EIO;
					}
				case READ:
					try{
						// check if file exists on the cache already
						synchronized (cache_lock) {
							if (cache.containsKey(cache_path)) {
								file = cache.get(cache_path);
							}
							else {
								// check if file exists on server
								if (max_ver == 0) {
									return Errors.ENOENT;
								}

								// check if file exists on proxy
								file = new File(cache_path);
								if (!file.exists()) {
									// create parent directories if they exist
									if (file.getParentFile() != null) {
										file.getParentFile().mkdirs();
									}
									// create the file
									file.createNewFile();
								}

								// copy over contents from server copy to proxy copy
								length = stub.getFileLength(path);
								curr_pos = 0;
								tmp_raf = new RandomAccessFile(file, "rw");
								while (curr_pos < length) {
									buf = stub.getFileInfo(path, curr_pos);
									// System.err.printf("open (read): buf size [%d] and curr_pos [%d]\n", buf.length, curr_pos);
									tmp_raf.seek(curr_pos);
									tmp_raf.write(buf);
									curr_pos += buf.length;
								}
								cache.put(cache_path, file);
							}
							cache.addClient(cache_path);
						}

						fd = getFd();
						fd_paths.put(fd, cache_path);

						if(!file.isDirectory()) {
							fd_raf = new RandomAccessFile(file, "r");
							fd_files.put(fd, fd_raf);
						}
						return fd;
					} catch (IOException e) {
						System.err.println("open (read): " + e.toString());
						return EIO;
					}
				case WRITE:
				try{
					// check if file exists on the cache already
					synchronized (cache_lock) {
						if (cache.containsKey(cache_path)) {
							file = cache.get(cache_path);

							// update file from server
							tmp_raf = new RandomAccessFile(file, "rw");
						}
						else {
							// check if file exists on server
							if (max_ver == 0) {
								return Errors.ENOENT;
							}

							// check if file exists on proxy
							file = new File(cache_path);
							if (file.isDirectory()) {
								return Errors.EISDIR;
							}
							if (!file.exists()) {
								// create parent directories if they exist
								if (file.getParentFile() != null) {
									file.getParentFile().mkdirs();
								}
								file.createNewFile();
							}

							// copy over contents from server copy to proxy copy
							length = stub.getFileLength(path);
							curr_pos = 0;
							tmp_raf = new RandomAccessFile(file, "rw");
							while (curr_pos < length) {
								buf = stub.getFileInfo(path, curr_pos);
								tmp_raf.seek(curr_pos);
								tmp_raf.write(buf);
								curr_pos += buf.length;
							}
							cache.put(cache_path, file);
						}
					}

					// create fd copy
					fd = getFd();
					min_path = min_path + "-" + max_ver;
					fd_path = cache_dir + "/" + min_path + "-w" + fd;
					System.err.println(fd_path);
					fd_file = new File(fd_path);
					if (fd_file.getParentFile() != null) {
						fd_file.getParentFile().mkdirs();
					}
					fd_file.createNewFile();
					fd_file.setReadable(true, false);
					fd_file.setWritable(true, false);

					// copy over contents from proxy copy to fd copy
					fd_raf = new RandomAccessFile(fd_file, "rw");
					length = tmp_raf.length();
					curr_pos = 0;
					tmp_raf.seek(0);
					while (curr_pos < length) {
						if (length - curr_pos > CHUNK_SIZE) {
							buf_size = CHUNK_SIZE;
						}
						else {
							buf_size = (int)(length - curr_pos);
						}
						buf = new byte[buf_size];
						tmp_raf.seek(curr_pos);
						tmp_raf.read(buf);
						fd_raf.seek(curr_pos);
						fd_raf.write(buf);
						curr_pos += buf_size;
					}
					fd_raf.seek(0);

					// update all hash maps
					fd_paths.put(fd, min_path);
					fd_files.put(fd, fd_raf);
					cache.put(fd_path, fd_file);
					cache.addClient(fd_path);
					return fd;
				} catch (IOException e) {
					System.err.println("open (write): " + e.toString());
					return EIO;
				}
				default:
					return Errors.EINVAL;
			}
		}

		/*
		 * Function: close
		 * This function implements the file close function.
		 * On close, file is updated on the server and local cache.
		 * 
		 * @param fd - file descriptor of file to close
		 * @return 0 on success, error value on failure
		 */
		public int close (int fd) {
			String base_path = fd_paths.get(fd);
			String fd_path;

			if (base_path.startsWith(cache_dir)) { // read only fd case
				fd_files.remove(fd);
				fd_paths.remove(fd);
				cache.get(base_path);
				cache.removeClient(base_path, false, fd);
				cache.checkStaleVersions(base_path);
				return 0;
			}

			fd_path = cache_dir + "/" + base_path + "-w" + fd;
			int old_ver = Integer.parseInt(base_path.substring(base_path.lastIndexOf("-")+1));
			int new_ver = old_ver + 1;
			String old_path = cache_dir + "/" + base_path.substring(0, base_path.lastIndexOf("-")+1) + old_ver;
			String cache_path = cache_dir + "/" + base_path.substring(0, base_path.lastIndexOf("-")+1) + new_ver;
			String server_path = base_path.substring(0, base_path.lastIndexOf("-"));

			File file = new File(fd_path);
			if (!file.exists()) {
				return Errors.ENOENT;
			}

			if(file.isDirectory()) {
				fd_paths.remove(fd);
				return 0;
			}
			try {
				RandomAccessFile close_raf = fd_files.get(fd);
				if (file.canWrite()) {
					File cache_file;
					cache_file = new File(cache_path);
					cache_file.createNewFile();
					cache.checkStaleVersions(cache_path);
					cache.put(cache_path, cache_file);
					RandomAccessFile raf = new RandomAccessFile(cache_file, "rw");

					long length = close_raf.length();
					long old_length = raf.length();
					long curr_pos = 0;
					int buf_size;
					while (curr_pos < length) {
						if (length - curr_pos > CHUNK_SIZE) {
							buf_size = CHUNK_SIZE;
						}
						else {
							buf_size = (int)(length - curr_pos);
						}

						byte[] buf = new byte[buf_size];
						raf.seek(curr_pos);
						raf.write(buf); // clear cache version

						close_raf.seek(curr_pos);
						close_raf.read(buf);
						stub.updateFile(server_path, buf, curr_pos); // update server
						raf.seek(curr_pos);
						raf.write(buf); // update cache version
						curr_pos += buf_size;
					}
					synchronized (cache_lock) {
						cache.curr_size += (length - old_length);
					}
				}
				close_raf.close();
				cache.removeClient(old_path, true, fd);
				fd_files.remove(fd);
				fd_paths.remove(fd);
				return 0;
			} catch (IOException e) {
				System.err.println("close: " + e.toString());
				return EIO;
			}
		}

		/*
		 * Function: write
		 * This function implements the file write function.
		 * 
		 * @param fd - file descriptor of file to write to
		 * @param buf - buffer of content to write to file
		 * @return number of bytes written to file
		 */
		public long write (int fd, byte[] buf) {
			if (buf == null) {
				return Errors.EBADF;
			}
			if (!fd_paths.containsKey(fd)) {
				return Errors.EBADF;
			}

			String fd_path = cache_dir + "/" + fd_paths.get(fd) + "-w" + fd;
			File file = new File(fd_path);
			try {
				if (file.isDirectory()) {
					return Errors.EISDIR;
				}
				if (!file.exists()) {
					return Errors.EBADF;
				}
				if (!file.canWrite()) {
					return Errors.EBADF;
				}
			} catch (SecurityException s) {
				System.err.println("write: " + s.toString());
				return Errors.EBADF;
			}

			RandomAccessFile write_raf = fd_files.get(fd);
			try {
				write_raf.write(buf);
				return (long) buf.length;
			} catch (IOException e) {
				System.err.println("write: " + e.toString());
				return -EIO;
			}
		}

		/*
		 * Function: read
		 * This function implements the file read function.
		 * 
		 * @param fd - file descriptor of file to read from
		 * @param buf - buffer to read content into
		 * @return number of bytes read into buf
		 */
		public long read (int fd, byte[] buf) {
			if (!fd_paths.containsKey(fd)) {
				return Errors.EBADF;
			}
			String fd_path = fd_paths.get(fd);

			// check if the fd corresponds to a read-only fd
			if (!fd_path.startsWith(cache_dir)) {
				fd_path = cache_dir + "/" + fd_paths.get(fd) + "-w" + fd;
			}

			File file = new File(fd_path);
			try {
				if (file.isDirectory()) {
					return Errors.EISDIR;
				}
				if (!file.exists()) {
					return Errors.ENOENT;
				}
				if (!file.canRead()) {
					return Errors.EBADF;
				}
			} catch (SecurityException s) {
				System.err.println("read: " + s.toString());
				return Errors.EBADF;
			}

			RandomAccessFile read_raf = fd_files.get(fd);
			try {
				long bytes_read = read_raf.read(buf);
				if (bytes_read == -1) {
					return 0;
				}
				return bytes_read;
			} catch (IOException e) {
				System.err.println("read: " + e.toString());
				return EIO;
			}
		}

		/*
		 * Function: lseek
		 * This function implements the file lseek function.
		 * 
		 * @param fd - file descriptor of file
		 * @param pos - file pointer offset
		 * @param o - where to offset from
		 * @return new file pointer position
		 */
		public long lseek (int fd, long pos, LseekOption o) {
			if (!fd_files.containsKey(fd)) {
				return Errors.EBADF;
			}

			String fd_path = fd_paths.get(fd);
			if (!fd_path.startsWith(cache_dir)) {
				fd_path = cache_dir + "/" + fd_paths.get(fd) + "-w" + fd;
			}

			File file = new File(fd_path);
			if (file.isDirectory()) {
				return Errors.EBADF;
			}
			if (!file.exists()) {
				return Errors.EBADF;
			}

			RandomAccessFile lseek_raf = fd_files.get(fd);
			try {
				long new_pos;
				switch (o) {
					case FROM_CURRENT:
						new_pos = lseek_raf.getFilePointer() + pos;
						if (new_pos < 0) {
							return Errors.EINVAL;
						}
						lseek_raf.seek(new_pos);
						return new_pos; 
					case FROM_END:
						new_pos = lseek_raf.length() + pos;
						if (new_pos < 0) {
							return Errors.EINVAL;
						}
						lseek_raf.seek(new_pos);
						return new_pos;
					case FROM_START:
						if (pos < 0) {
							return Errors.EINVAL;
						}
						lseek_raf.seek(pos);
						return pos;
					default:
						return Errors.EINVAL;
				}
			} catch (IOException e) {
				System.err.println("lseek: " + e.toString());
				return EIO;
			}
		}

		/*
		 * Function: unlink
		 * This function implements the file unlink function.
		 * 
		 * @param path - path of file to delete
		 * @return 0 on success, error value on failure
		 */
		public int unlink (String path) {
			String min_path = Path.of(path).normalize().toString();
			File file = new File(min_path);

			if (file.isDirectory() && file.list().length != 0) {
				return Errors.ENOTEMPTY;
			}
			try {
				// need to check to make sure it exists on the server side
				int success = stub.deleteFile(min_path);
				if (success != 0) {
					return Errors.ENOENT;
				}
			} catch (IOException e) {
				System.err.println("unlink: " + e.toString());
				return EIO;
			}
			return 0;
		}

		public void clientdone () {
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient () {
			return new FileHandler();
		}
	}

	/*
	 * Function: main
	 * Sets up the proxy and communication with the server
	 * 
	 * @param args[0] - hostIP
	 * @param args[1] - port value
	 * @param args[2] - cache directory
	 * @param args[3] - maximum cache size
	 */
	public static void main (String[] args) throws IOException {
		fd_files = new ConcurrentHashMap<Integer, RandomAccessFile>();
		fd_paths = new ConcurrentHashMap<Integer, String>();

		hostIP = args[0];
		port = Integer.parseInt(args[1]);
		cache_dir = args[2];
		max_size = Integer.parseInt(args[3]);
		cache = new Cache(max_size);
		curr_fd = 3;

		try {
			Registry registry = LocateRegistry.getRegistry(hostIP, port);
			stub = (RMIInterface) registry.lookup("RMIInterface");
		} catch (RemoteException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (NotBoundException e) {
			e.printStackTrace();
			System.exit(1);
		}
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}