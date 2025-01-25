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

#define MAXMSGLEN 100
#define OFFSET 100

// The following line declares a function pointer with the same prototype as the open function.  
int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT
int (*orig_close)(int fd);
ssize_t (*orig_read)(int fd, void *buf, size_t count);
ssize_t (*orig_write)(int fd, const void *buf, size_t count);
off_t (*orig_lseek)(int fd, off_t offset, int whence);
int (*orig_stat)(const char *pathname, struct stat *statbuf);
int (*orig_unlink)(const char *pathname);
ssize_t (*orig_getdirentries)(int fd, char *buf, size_t nbytes, off_t *basep);
struct dirtreenode* (*orig_getdirtree)(char *path);
void (*orig_freedirtree)(struct dirtreenode* dt);

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

int sockfd;

// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
	mode_t m = 0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
		fprintf(stderr, "modified mode\n");
	}

	// *** marshall data here ***
	int length = sizeof(rpc_header) + sizeof(open_header) + strlen(pathname) + 1;
	char *msg = malloc(length); 
	char buf[MAXMSGLEN];
	int offset = 0;
	int rv;

	// send message to server
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
	fprintf(stderr, "converted pathname: %s\n", msg + offset);
	send(sockfd, msg, length, 0); 

	// get message back
	rv = recv(sockfd, buf, sizeof(int), 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	int rfd = atoi(buf);

	rv = recv(sockfd, buf, sizeof(int), 0);	// get errno 
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	errno = atoi(buf);

	fprintf(stderr, "open: returned fd [%d] with errno [%d]\n", rfd, errno); 
	return rfd;
}

// need to add functions for: close, read, write, lseek, stat, unlink, getdirentries
int close(int fd) {
	char *msg = malloc(sizeof(rpc_header) + sizeof(close_header));
	char buf[MAXMSGLEN];
	int rv;

	// *** marshall data here ***
	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = CLOSE;
	head->size = sizeof(close_header);
	memcpy(msg, head, sizeof(rpc_header));

	close_header *cHead = malloc(sizeof(close_header));
	cHead->fd = fd;
	memcpy(msg + sizeof(rpc_header), cHead, sizeof(close_header));
	fprintf(stderr, "closing fd: %d\n", fd);
	send(sockfd, msg, sizeof(rpc_header) + sizeof(close_header), 0);

	// get message back
	rv = recv(sockfd, buf, sizeof(int), 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	int rstate = atoi(buf);

	rv = recv(sockfd, buf, sizeof(int), 0);	// get errno
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	errno = atoi(buf);

	fprintf(stderr, "close: returned state [%d] with errno [%d]\n", rstate, errno);

	return rstate;
}

ssize_t read(int fd, void *buf, size_t count) {
	char *msg = malloc(sizeof(rpc_header));
	char buf1[MAXMSGLEN];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = READ;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf1, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf1[rv] = 0;				// null terminate string to print

	return orig_read(fd, buf, count);
	// char *msg = malloc(sizeof(rpc_header) + sizeof(read_header));
	// char buf1[MAXMSGLEN+1];
	// int rv;

	// // send message to server
	// rpc_header *head = malloc(sizeof(rpc_header));
	// head->opcode = READ;
	// head->size = sizeof(rpc_header);
	// memcpy(msg, head, sizeof(rpc_header));

	// read_header *rHead = malloc(sizeof(read_header));
	// rHead->fd = fd;
	// rHead->count = count;
	// memcpy(msg + sizeof(rpc_header), rHead, sizeof(read_header));
	// send(sockfd, msg, sizeof(rpc_header) + sizeof(read_header), 0);

	// // get message back
	// rv = recv(sockfd, buf1, sizeof(ssize_t), 0);	// get message
	// if (rv < 0) err(1,0);			// in case something went wrong
	// buf1[rv] = 0;				// null terminate string to print
	// ssize_t read_bytes = (ssize_t) atoi(buf1);

	// rv = recv(sockfd, buf1, sizeof(int), 0);	// get errno
	// if (rv < 0) err(1,0);			// in case something went wrong
	// buf1[rv] = 0;				// null terminate string to print
	// errno = atoi(buf1);

	// memcpy(buf, msg + sizeof(ssize_t) + sizeof(int), count);

	// fprintf(stderr, "read: returned [%ld] read bytes with errno [%d]\n", read_bytes, errno);
	// return read_bytes;
}

ssize_t write(int fd, const void *buf, size_t count) {
	int length = sizeof(rpc_header) + sizeof(write_header) + count;
	// fprintf(stderr, "length [%d]\n", length);
	char *msg = malloc(length);
	char buf1[MAXMSGLEN];
	int offset = 0;
	int rv;
	ssize_t write_bytes = 0;

	// *** marshall data here ***
	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = WRITE;
	head->size = sizeof(write_header) + count;
	memcpy(msg, head, sizeof(rpc_header));
	offset += sizeof(rpc_header);
	// fprintf(stderr, "offset: %d\n", offset);

	write_header *wHead = malloc(sizeof(write_header));
	wHead->fd = fd;
	wHead->count = count;
	memcpy(msg + offset, wHead, sizeof(write_header));
	offset += sizeof(write_header);
	// fprintf(stderr, "offset: %d\n", offset);

	memcpy(msg + offset, buf, count);

	fprintf(stderr, "writing [%ld] bytes to fd [%d]\n", wHead->count, wHead->fd);
	send(sockfd, msg, length, 0);

	// get message back
	rv = recv(sockfd, buf1, sizeof(ssize_t), 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf1[rv] = 0;				// null terminate string to print
	write_bytes = (ssize_t) atoi(buf1);

	rv = recv(sockfd, buf1, sizeof(int), 0);	// get errno
	if (rv < 0) err(1,0);			// in case something went wrong
	buf1[rv] = 0;				// null terminate string to print
	errno = atoi(buf1);

	fprintf(stderr, "write: bytes written [%ld] with errno [%d]\n", write_bytes, errno);

	return write_bytes;
}

off_t lseek(int fd, off_t offset, int whence) {
	char *msg = malloc(sizeof(rpc_header));
	char buf[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = LSEEK;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print

	return orig_lseek(fd, offset, whence);
}

int stat(const char *pathname, struct stat *statbuf) {
	char *msg = malloc(sizeof(rpc_header));
	char buf[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = STAT;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	return orig_stat(pathname, statbuf);
}

int unlink(const char *pathname) {
	char *msg = malloc(sizeof(rpc_header));
	char buf[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = UNLINK;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	return orig_unlink(pathname);
}

ssize_t getdirentries(int fd, char *buf, size_t nbytes, off_t *basep) {
	char *msg = malloc(sizeof(rpc_header));
	char buf1[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = GETDIRENTRIES;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf1, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf1[rv] = 0;				// null terminate string to print
	return orig_getdirentries(fd, buf, nbytes, basep);
}

struct dirtreenode* getdirtree(char *path) {
	char *msg = malloc(sizeof(rpc_header));
	char buf[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = GETDIRTREE;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	return orig_getdirtree(path);
}

void freedirtree(struct dirtreenode* dt) {
	char *msg = malloc(sizeof(rpc_header));
	char buf[MAXMSGLEN+1];
	int rv;

	// send message to server
	rpc_header *head = malloc(sizeof(rpc_header));
	head->opcode = FREEDIRTREE;
	head->size = 0;
	memcpy(msg, head, sizeof(rpc_header));
	send(sockfd, msg, sizeof(rpc_header), 0);

	// get message back
	rv = recv(sockfd, buf, MAXMSGLEN, 0);	// get message
	if (rv < 0) err(1,0);			// in case something went wrong
	buf[rv] = 0;				// null terminate string to print
	return orig_freedirtree(dt);
}

// This function is automatically called when program is started
void _init(void) {
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
	if (serverport) fprintf(stderr, "Got environment variable serverport15440: %s\n", serverport);
	else {
		fprintf(stderr, "Environment variable serverport15440 not found.  Using 15440\n");
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


