/*
 * File: mylib.c
 * Description: Implements an interposition library for Project 1.
 * Author: Joseph Jia (josephji)
 *
 * This file implements the interposition library for many file operations 
 * using RPC calls to the server.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>
#include <string.h>
#include <err.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>

#define MAXMSGLEN 10000
#define OFFSET 10000

// Function pointer declartion to original functions
int (*orig_open)(const char *pathname, int flags, ...);
int (*orig_close)(int fd);
ssize_t (*orig_read)(int fd, void *buf, size_t count);
ssize_t (*orig_write)(int fd, const void *buf, size_t count);
off_t (*orig_lseek)(int fd, off_t offset, int whence);
int (*orig_stat)(const char *pathname, struct stat *statbuf);
int (*orig_unlink)(const char *pathname);
ssize_t (*orig_getdirentries)(int fd, char *buf, size_t nbytes, off_t *basep);
struct dirtreenode* (*orig_getdirtree)(char *path);
void (*orig_freedirtree)(struct dirtreenode* dt);

// Struct definitions to send data over to server
struct dirtreenode {
	char *name;
	int num_subdirs;
	struct dirtreenode **subdirs;
};

typedef struct {
	int opcode;
	size_t size;
} rpc_header;

typedef struct {
	int path_length;
	int flags;
	mode_t mode;
} open_header;

typedef struct {
	int fd;
} close_header;

typedef struct {
	int fd;
	size_t count;
} read_header;

typedef struct {
	int fd;
	size_t count;
} write_header;

typedef struct {
	int fd;
	off_t offset;
	int whence;
} lseek_header;

typedef struct {
	int path_length;
} stat_header;

typedef struct {
	int path_length;
} unlink_header;

typedef struct {
	int fd;
	size_t nbytes;
	off_t offset;
} direntries_header;

typedef struct {
	int path_length;
} gettree_header;

typedef struct {
	int serialized_length;
} freetree_header;

// Enumeration of different operations to make casing easier
enum {
	OPEN,
	CLOSE,
	READ,
	WRITE,
	LSEEK,
	STAT,
	UNLINK,
	GETDIRENTRIES,
	GETDIRTREE,
	FREEDIRTREE
};

// Global socket file descriptor
int sockfd;

/*
 * Function: deserialize_tree
 *
 * This function recursively deserializes a bytestream to create a directory tree.
 *
 * Arguments:
 *   serialized - serialized bytestream
 *   offset - current memory offset from serialized start
 *   curr_node - current node within the directory tree
 */
struct dirtreenode *deserialize_tree (char *serialized, int *offset, struct dirtreenode *curr_node) {
	int num_subdirs = 0, name_length = 0;
	char *name;

	// get all information for current node
	memcpy(&name_length, serialized + *offset, sizeof(int));
	*offset += sizeof(int);

	name = malloc(name_length);
	memcpy(name, serialized + *offset, name_length);
	*offset += name_length;

	memcpy(&num_subdirs, serialized + *offset, sizeof(int));
	*offset += sizeof(int);

	curr_node = malloc(sizeof(struct dirtreenode));
	curr_node->name = name;
	curr_node->num_subdirs = num_subdirs;

	if(num_subdirs > 0) {
		curr_node->subdirs = malloc(num_subdirs * sizeof(struct dirtreenode));
		// recursively iterate to reconstruct tree
		for(int i = 0; i < num_subdirs; i++) {
			curr_node->subdirs[i] = malloc(sizeof(struct dirtreenode));
			curr_node->subdirs[i] = deserialize_tree(serialized, offset, curr_node->subdirs[i]);
		}
	}
	else curr_node->subdirs = NULL;
	return curr_node;
}

/*
 * Function: free_tree
 *
 * This function recursively frees a directory tree.
 *
 * Arguments:
 *   node - current node of the directory tree
 */
void free_tree(struct dirtreenode *node) {
	int num_subdirs = node->num_subdirs;
	if (num_subdirs > 0) {
		for (int i = 0; i < num_subdirs; i++) {
			free_tree(node->subdirs[i]);
		}
	}
	free(node->name);
	free(node->subdirs);
	free(node);
}

/*
 * Function: open
 *
 * This is the interposed open function.
 * When open is called locally, this function makes an RPC call for open().
 *
 * Arguments:
 *   pathname - file path to open
 *   flags - flags to determine how the file should be opened
 *   mode - specifies if a new file is created (optional)
 */
int open (const char *pathname, int flags, ...) {
	fprintf(stderr, "open.\n");
	mode_t m = 0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}

	int length = sizeof(rpc_header) + sizeof(open_header) + strlen(pathname) + 1;
	char *msg = malloc(length); 
	char buf[MAXMSGLEN];
	int offset = 0;
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header)); 
	head->opcode = OPEN;
	head->size = sizeof(open_header) + strlen(pathname) + 1;
	memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);

	open_header *oHead = malloc(sizeof(open_header));
	oHead->path_length = strlen(pathname) + 1;
	oHead->flags = flags;
	oHead->mode = m;
	memcpy(msg + offset, oHead, sizeof(open_header));
	offset += sizeof(open_header);
	memcpy(msg + offset, pathname, strlen(pathname) + 1);

	fprintf(stderr, "open: sent packet size [%ld] with pathname [%s]\n", head->size, pathname);
	send(sockfd, msg, length, 0); 

	// receive and parse message from server
	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	int rfd;
	memcpy(&rfd, buf, sizeof(int));

	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	if (rfd < 0) {
		fprintf(stderr, "open: received fd [%d] with errno [%d], but sent fd [%d]\n", rfd, errno, rfd); 
		return rfd;
	}
	fprintf(stderr, "open: received fd [%d] with errno [%d], but sent fd [%d]\n", rfd, errno, rfd + OFFSET); 
	free(msg);
	free(head);
	free(oHead);
	return rfd + OFFSET;
}

/*
 * Function: close
 *
 * This is the interposed close function.
 * When close is called locally, this function makes an RPC call for close().
 *
 * Arguments:
 *   fd - file descriptor to close
 */
int close (int fd) {
	fprintf(stderr, "close.\n");
	if (fd < OFFSET) {
		return orig_close(fd);
	}

	char *msg = malloc(sizeof(rpc_header) + sizeof(close_header));
	char buf[MAXMSGLEN];
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = CLOSE;
	head->size = sizeof(close_header);
	memcpy(msg, head, sizeof(rpc_header));

	close_header *cHead = malloc(sizeof(close_header));
	cHead->fd = fd - OFFSET;
	memcpy(msg + sizeof(rpc_header), cHead, sizeof(close_header));

	fprintf(stderr, "close: sent packet size [%ld]\n", head->size);
	send(sockfd, msg, sizeof(rpc_header) + sizeof(close_header), 0);

	// receive and parse message from server
	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	int rstate;
	memcpy(&rstate, buf, sizeof(int));

	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	fprintf(stderr, "close: received state [%d] with errno [%d]\n", rstate, errno);
	free(msg);
	free(head);
	free(cHead);
	return rstate;
}

/*
 * Function: read
 *
 * This is the interposed read function.
 * When read is called locally, this function makes an RPC call for read().
 *
 * Arguments:
 *   fd - file descriptor to read from
 *   buf - buffer to store contents read
 *   count - number of bytes to read
 */
ssize_t read (int fd, void *buf, size_t count) {
	fprintf(stderr, "read.\n");
	if (fd < OFFSET) {
		return orig_read(fd, buf, count);
	}

	char *msg = malloc(sizeof(rpc_header) + sizeof(read_header));
	char buf1[MAXMSGLEN];
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = READ;
	head->size = sizeof(read_header);
	memcpy(msg, head, sizeof(rpc_header));

	read_header *read_head = malloc(sizeof(read_header));
	read_head->fd = fd - OFFSET;
	read_head->count = count;
	memcpy(msg + sizeof(rpc_header), read_head, sizeof(read_header));

	fprintf(stderr, "read: sent packet size [%ld] with fd [%d]\n", head->size, read_head->fd);
	send(sockfd, msg, sizeof(rpc_header) + sizeof(read_header), 0);

	// receive and parse message from server
	rv = recv(sockfd, buf1, sizeof(ssize_t), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	ssize_t bytes_read;
	memcpy(&bytes_read, buf1, sizeof(ssize_t));

	rv = recv(sockfd, buf1, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	memcpy(&errno, buf1, sizeof(int));

	// continuously read until entire buf value is copied over
	int got = 0, offset = 0, to_read = 0;
	while (got < bytes_read) {
		if (bytes_read - got > MAXMSGLEN) {
			to_read = MAXMSGLEN;
		}
		else to_read = bytes_read - got;
		rv = recv(sockfd, buf1, to_read, 0);
		if (rv < 0) err(1,0);
		buf1[rv] = 0;
		memcpy(buf + offset, buf1, rv);
		offset += rv;
		got += rv;
	}

	fprintf(stderr, "read: received bytes read [%d] and errno [%d]\n", got, errno);
	free(msg);
	free(head);
	free(read_head);
	return bytes_read;
}

/*
 * Function: write
 *
 * This is the interposed write function.
 * When write is called locally, this function makes an RPC call for write().
 *
 * Arguments:
 *   fd - file descriptor to write from
 *   buf - buffer with contents to write
 *   count - number of bytes to write from the buffer
 */
ssize_t write (int fd, const void *buf, size_t count) {
	fprintf(stderr, "write.\n");
	if (fd < OFFSET) {
		return orig_write(fd, buf, count);
	}

	int length = sizeof(rpc_header) + sizeof(int) + sizeof(size_t) + count;
	char *msg = malloc(length);
	char buf1[MAXMSGLEN];
	int offset = 0;
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = WRITE;
	head->size = sizeof(int) + sizeof(size_t) + count;
	memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);

	int new_fd = fd - OFFSET;
	memcpy(msg + offset, &new_fd, sizeof(int));
	offset += sizeof(int);
	memcpy(msg + offset, &count, sizeof(size_t));
	offset += sizeof(size_t);

	memcpy(msg + offset, buf, count);

	fprintf(stderr, "write: sent packet size [%ld] with fd [%d] and count [%ld]\n", head->size, new_fd, count);
	send(sockfd, msg, length, 0);

	// receive and parse message from server
	rv = recv(sockfd, buf1, sizeof(ssize_t), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	ssize_t write_bytes;
	memcpy(&write_bytes, buf1, sizeof(ssize_t));

	rv = recv(sockfd, buf1, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	memcpy(&errno, buf1, sizeof(int));

	fprintf(stderr, "write: received bytes written [%ld] and errno [%d]\n", write_bytes, errno);
	free(msg);
	free(head);
	return write_bytes;
}

/*
 * Function: lseek
 *
 * This is the interposed lseek function.
 * When lseek is called locally, this function makes an RPC call for lseek().
 *
 * Arguments:
 *   fd - file descriptor to close
 *   offset - number of bytes to move the file offset
 *   whence - specifies where the offset starts from
 */
off_t lseek (int fd, off_t offset, int whence) {
	fprintf(stderr, "lseek.\n");
	if (fd < offset) {
		return orig_lseek(fd, offset, whence);
	}

	char *msg = malloc(sizeof(rpc_header) + sizeof(lseek_header));
	char buf[MAXMSGLEN];
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = LSEEK;
	head->size = sizeof(lseek_header);
	memcpy(msg, head, sizeof(rpc_header));

	lseek_header *lseek_head = malloc(sizeof(lseek_header));
	lseek_head->fd = fd - OFFSET;
	lseek_head->offset = offset;
	lseek_head->whence = whence;
	memcpy(msg + sizeof(rpc_header), lseek_head, sizeof(lseek_header));

	fprintf(stderr, "lseek: send packet size [%ld]\n", head->size);
	send(sockfd, msg, sizeof(rpc_header) + sizeof(lseek_header), 0);

	// receive and parse message from server
	rv = recv(sockfd, buf, sizeof(off_t), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	off_t offset_location;
	memcpy(&offset_location, buf, sizeof(off_t));

	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	fprintf(stderr, "lseek: received new location [%ld] and errno [%d]\n", offset_location, errno);
	free(msg);
	free(head);
	free(lseek_head);
	return offset_location;
}

/*
 * Function: stat
 *
 * This is the interposed stat function.
 * When stat is called locally, this function makes an RPC call for stat().
 *
 * Arguments:
 *   pathname - file path
 *   statbuf - stored file information from the specified file path
 */
int stat (const char *pathname, struct stat *statbuf) {
	fprintf(stderr, "stat.\n");
	int length = sizeof(rpc_header) + sizeof(stat_header) + sizeof(struct stat) + strlen(pathname) + 1;
	int path_length = strlen(pathname) + 1;
	char *msg = malloc(length);
	char buf[MAXMSGLEN];
	int offset = 0;
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = STAT;
	head->size = sizeof(stat_header) + sizeof(struct stat) + path_length;
	memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);

	stat_header *stat_head = malloc(sizeof(stat_header));
	stat_head->path_length = path_length;
	memcpy(msg + offset, stat_head, sizeof(stat_header));
	offset += sizeof(stat_header);

	memcpy(msg + offset, pathname, path_length);
	offset += path_length;
	memcpy(msg + offset, statbuf, sizeof(struct stat));

	fprintf(stderr, "stat: sent packet size [%ld] with path [%s]\n", head->size, msg + sizeof(rpc_header) + sizeof(stat_header));
	send(sockfd, msg, length, 0);

	// receive and parse message from server
	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	int result;
	memcpy(&result, buf, sizeof(int));

	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	fprintf(stderr, "stat: received result [%d] and errno [%d]\n", result, errno);
	free(msg);
	free(head);
	free(stat_head);
	return result;
}

/*
 * Function: unlink
 *
 * This is the interposed unlink function.
 * When unlink is called locally, this function makes an RPC call for unlink().
 *
 * Arguments:
 *   pathname - file path to delete
 */
int unlink (const char *pathname) {
	fprintf(stderr, "unlink.\n");
	int length = sizeof(rpc_header) + sizeof(unlink_header) + strlen(pathname) + 1;
	int path_length = strlen(pathname) + 1;
	int offset = 0;
	char *msg = malloc(length);
    char buf[MAXMSGLEN];
    int rv;

    // create and send message to server
    rpc_header *head = malloc(sizeof(rpc_header));
    head->opcode = UNLINK;
    head->size = sizeof(unlink_header) + strlen(pathname) + 1;
    memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);

	unlink_header *unlink_head = malloc(sizeof(unlink_header));
	unlink_head->path_length = path_length;
	memcpy(msg + offset, unlink_head, sizeof(unlink_header));
	offset += sizeof(unlink_header);
	memcpy(msg + offset, pathname, path_length);

	fprintf(stderr, "unlink: sent packet size [%ld]\n", head->size);
    send(sockfd, msg, length, 0);

    // receive and parse message from server
    rv = recv(sockfd, buf, sizeof(int), 0);
    if (rv < 0) err(1,0);
    buf[rv] = 0;
	int unlinked;
	memcpy(&unlinked, buf, sizeof(int));

	rv = recv(sockfd, buf, sizeof(int), 0);
    if (rv < 0) err(1,0);
    buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	fprintf(stderr, "unlink: received unlink [%d] and errno [%d]\n", unlinked, errno);
	free(msg);
	free(head);
	free(unlink_head);
    return unlinked;
}

/*
 * Function: getdirentries
 *
 * This is the interposed getdirentries function.
 * When getdirentries is called locally, this function makes an RPC call for getdirentries().
 *
 * Arguments:
 *   fd - file descriptor of directory
 *   buf - buffer to store directory entries
 *   nbytes - size of buf, in bytes
 *   basep - offset value
 */
ssize_t getdirentries (int fd, char *buf, size_t nbytes, off_t *basep) {
	fprintf(stderr, "getdirentries.\n");
	if (fd < OFFSET) {
		return orig_getdirentries(fd, buf, nbytes, basep);
	}

	char *msg = malloc(sizeof(rpc_header) + sizeof(direntries_header));
	char buf1[MAXMSGLEN];
	int got, byte_offset, to_read;
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = GETDIRENTRIES;
	head->size = sizeof(direntries_header);
	memcpy(msg, head, sizeof(rpc_header));

	direntries_header *dir_head = malloc(sizeof(direntries_header));
	dir_head->fd = fd - OFFSET;
	dir_head->nbytes = nbytes;
	dir_head->offset = *basep;
	memcpy(msg + sizeof(rpc_header), dir_head, sizeof(direntries_header));

	fprintf(stderr, "getdirentires: sent packet size [%ld]\n", head->size);
	send(sockfd, msg, sizeof(rpc_header) + sizeof(direntries_header), 0);

	// receive and parse message from server
	rv = recv(sockfd, buf1, sizeof(ssize_t), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	ssize_t nbytes_read;
	memcpy(&nbytes_read, buf1, sizeof(ssize_t));

	rv = recv(sockfd, buf1, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	// continuously receive buf contents until all received
	got = 0;
	byte_offset = 0;
	while (got < nbytes_read) {
		if(nbytes_read - got < MAXMSGLEN) {
			to_read = nbytes_read - got;
		}
		else to_read = MAXMSGLEN;
		rv = recv(sockfd, buf1, to_read, 0);
		if (rv < 0) err(1,0);
		buf1[rv] = 0;
		memcpy(buf + byte_offset, buf1, rv);
		byte_offset += rv;
		got += rv;
	}

	rv = recv(sockfd, buf1, sizeof(off_t), 0);
	if (rv < 0) err(1,0);
	buf1[rv] = 0;
	memcpy(basep, buf1, sizeof(off_t));

	fprintf(stderr, "getdirentries: received nbytes read [%ld], offset [%ld], and errno [%d]\n", nbytes_read, *basep, errno);
	free(msg);
	free(head);
	free(dir_head);
	return nbytes_read;
}

/*
 * Function: getdirentries
 *
 * This is the interposed getdirtree function.
 * When getdirtree is called locally, this function makes an RPC call for getdirtree().
 *
 * Arguments:
 *   path - file path to create directory tree
 */
struct dirtreenode* getdirtree (char *path) {
	int length = sizeof(rpc_header) + sizeof(gettree_header) + strlen(path) + 1;
	char *msg = malloc(length);
	char *serialized_tree;
	char buf[MAXMSGLEN];
	int offset = 0;
	int rv;

	// create and send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = GETDIRTREE;
	head->size = sizeof(gettree_header) + strlen(path) + 1;
	memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);

	gettree_header *gettree_head = malloc(sizeof(gettree_header));
	gettree_head->path_length = strlen(path) + 1;
	memcpy(msg + offset, gettree_head, sizeof(gettree_header));
	offset += sizeof(gettree_header);
	memcpy(msg + offset, path, strlen(path) + 1);

	fprintf(stderr, "getdirtree: sent packet size [%ld]\n", head->size);
	send(sockfd, msg, length, 0);

	// receive and parse message from server
	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	memcpy(&errno, buf, sizeof(int));

	rv = recv(sockfd, buf, sizeof(int), 0);
	if (rv < 0) err(1,0);
	buf[rv] = 0;
	int tree_length;
	memcpy(&tree_length, buf, sizeof(int));

	// continuously read tree serialization until entire tree is received
	serialized_tree = malloc(tree_length);
	int got = 0, to_read = 0, byte_offset = 0;
	while (got < tree_length) {
		if(tree_length - got < MAXMSGLEN) {
			to_read = tree_length - got;
		}
		else to_read = MAXMSGLEN;
		rv = recv(sockfd, buf, to_read, 0);
		if (rv < 0) err(1,0);
		buf[rv] = 0;
		memcpy(serialized_tree + byte_offset, buf, rv);
		byte_offset += rv;
		got += rv;
	}
	struct dirtreenode *root = NULL;
	int *serialize_offset = malloc(sizeof(int));
	*serialize_offset = 0;
	root = deserialize_tree(serialized_tree, serialize_offset, root);

	fprintf(stderr, "getdirtree: received serialized tree [%d] and errno [%d]\n", tree_length, errno);
	free(msg);
	free(head);
	free(gettree_head);
	free(serialized_tree);
	free(serialize_offset);
	return root;
}

/*
 * Function: freedirtree
 *
 * This is the interposed freedirtree function.
 * When freedirtree is called locally, this function makes an RPC call for freedirtree().
 *
 * Arguments:
 *   dt - directory tree root node
 */
void freedirtree (struct dirtreenode* dt) {
	fprintf(stderr, "freedirtree.\n");
	free_tree(dt);
	return;
}

// This function is automatically called when program is started
void _init (void) {
	// set function pointer orig_open to point to the original open function
	orig_open = dlsym(RTLD_NEXT, "open");
	orig_close = dlsym(RTLD_NEXT, "close");
	orig_read = dlsym(RTLD_NEXT, "read");
	orig_write = dlsym(RTLD_NEXT, "write");
	orig_lseek = dlsym(RTLD_NEXT, "lseek");
	orig_stat = dlsym(RTLD_NEXT, "stat");
	orig_unlink = dlsym(RTLD_NEXT, "unlink");
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");
	orig_getdirtree = dlsym(RTLD_NEXT, "getdirtree");
	orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");

	// *** need to create socket here and never close it ***
	char *serverip;
	char *serverport;
	unsigned short port;
	int rv;
	struct sockaddr_in srv;
	
	// Get environment variable indicating the ip address of the server
	serverip = getenv("server15440");
	if (serverip) fprintf(stderr, "Got environment variable server15440: %s\n", serverip);
	else {
		fprintf(stderr, "Environment variable server15440 not found.  Using 127.0.0.1\n");
		serverip = "127.0.0.1";
	}
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) fprintf(stderr, "Got environment variable serverport15440: %s\n\n", serverport);
	else {
		fprintf(stderr, "Environment variable serverport15440 not found.  Using 15440\n\n");
		serverport = "15440";
	}
	port = (unsigned short) atoi(serverport);
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd < 0) err(1, 0);			// in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv < 0) err(1, 0);
}