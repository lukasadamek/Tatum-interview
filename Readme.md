### Tatum Gateway Team hiring task

Java application using the Vert.x framework to serve as a proxy for JSON-RPC 2.0 requests between clients and an Ethereum blockchain node.

##### How to run

1. Build using maven:
    - `maven clean package`
	- You can find fatJar in `target/EthereumProxyServer-1.0.0.jar`.


2. Create docker image:
	- `docker build -t proxyserver .`


3. Run docker container:
    - `docker run -p 8443:8443 proxyserver`
    - or you can define parameters: \
    `docker run -p 8443:8443 \ ` \
    `-v $(pwd)/keystore.jks:/app/keystore.jks \ ` \
    `-e PROXY_PORT="8443" \ ` \
    `-e ETHEREUM_NODE_URL="https://ethereum.publicnode.com" \ ` \
    `-e KEYSTORE_PATH="keystore.jks" \ ` \
    `-e KEYSTORE_PASSWORD="secretpassword" \ ` \
    `proxyserver`


##### Configuration

The application receives these optional environment variables

- `PROXY_PORT`: Port on which the proxy will listen (e.g., 8443) 
- `ETHEREUM_NODE_URL`: URL of the target Ethereum node 
- `KEYSTORE_PATH`: Path to the JKS keystore file 
- `KEYSTORE_PASSWORD`: Password for the keystore 
