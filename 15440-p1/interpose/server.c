#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>

#define MAXMSGLEN 100

typedef struct {
	int opcode;
	size_t size;
} rpc_header;

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

void handle_client(int sessfd) {
	int rv;
	char *msg = "hello from server";
	rpc_header *header = malloc(sizeof(rpc_header));
	// get messages and send replies to this client, until it goes away
	while ((rv = recv(sessfd, header, sizeof(rpc_header), 0)) > 0) {
		int op = header->opcode;
		// fprintf(stderr, "op: %d\n", op);
		switch (op) {
			case OPEN:
				fprintf(stdout, "open\n");
				break;
			case CLOSE:
				fprintf(stdout, "close\n");
				break;
			case READ:
				fprintf(stdout, "read\n");
				break;
			case WRITE:
				fprintf(stdout, "write\n");
				break;
			case LSEEK:
				fprintf(stdout, "lseek\n");
				break;
			case STAT:
				fprintf(stdout, "stat\n");
				break;
			case UNLINK:
				fprintf(stdout, "unlink\n");
				break;
			case GETDIRENTRIES:
				fprintf(stdout, "getdirentries\n");
				break;
			case GETDIRTREE:
				fprintf(stdout, "getdirtree\n");
				break;
			case FREEDIRTREE:
				fprintf(stdout, "freedirtree\n");
				break;
			default:
				fprintf(stdout, "other opcode\n");
				break;
		}
		// send reply
		// fprintf(stderr, "server replying to client: %s\n", msg);
		send(sessfd, msg, strlen(msg), 0);	// should check return value
	}
	// either client closed connection, or error
	if (rv < 0) err(1,0);
	close(sessfd);
}

int main(int argc, char**argv) {
	// char *msg="Hello from server";
	// char buf[MAXMSGLEN+1];
	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	// fprintf(stderr, "in server\n");
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short) atoi(serverport);
	else port = 15440;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd < 0) err(1, 0);			// in case of error
	// fprintf(stderr, "socket created\n");
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port
	// fprintf(stderr, "address structure setup\n");

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	// fprintf(stderr, "bind rv: %d\n", rv);
	if (rv < 0) err(1,0);
	// fprintf(stderr, "after bind\n");
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv < 0) err(1,0);
	// fprintf(stderr, "after listen\n");
	
	// main server loop, handle clients one at a time, quit after 10 clients
	while(1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		// fprintf(stderr, "before accept\n");
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		if (sessfd < 0) err(1, 0);
		// fprintf(stderr, "after accept\n");
		if (fork() == 0) {
			close(sockfd);
			handle_client(sessfd);
			exit(0);
		}
		close(sessfd);

	}
	
	// fprintf(stderr, "server shutting down cleanly\n");
	// close socket
	close(sockfd);

	return 0;
}

