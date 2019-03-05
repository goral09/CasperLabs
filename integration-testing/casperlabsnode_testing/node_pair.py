import docker



class NodePair:
    """
    Holds both a node and execution-engine docker container for one network node.
    """
    def __init__(self, client, node_number: int) -> None:
        self.client = client
        self.network = self._get_network()
        self.node_number = node_number
        self.ee_container = self._run_ee_container('latest')
        # self.node_container = self._run_node_container('latest')

    def __repr__(self) -> str:
        return f'<NodePair(node-{self.node_number})>'

    def _get_network(self):
        cl_network = [net for net in self.client.networks.list() if net.name == 'casperlabs']
        if cl_network:
            return cl_network[0]
        return self.client.networks.create(name='casperlabs', internal=False)

    def _run_node_container(self, version: str):
        """
          node:
    image: casperlabs/node:${CL_VERSION}
    container_name: node-${NODE_NUMBER}
    hostname: node-${NODE_NUMBER}
    volumes:
      # Volume for a socket to be created and shared with the execution engine.
      - socketvolume:/root/.casperlabs/sockets
      # Common bonds files. Don't map the .casperlabs directory itself so nodes can create SSL keys.
      # Using files created by running the node once as described in https://slack-files.com/TDVFB45LG-FFBGDQSBW-bad20239ec
      # Later all need to agree on the same validators so these are committed to git and mounted.
      - $PWD/../.casperlabs/genesis:/root/.casperlabs/genesis
      - $PWD/../.casperlabs/bootstrap:/root/.casperlabs/bootstrap
      - $PWD/../template/start-node.sh:/opt/docker/start-node.sh
    networks:
      - casperlabs
    environment:
      HOME: /root
      # Got the ID from the logs by running the node once. They keys are going to be the same for node-0.
      # i.e. "Listening for traffic on casperlabs://..."
      BOOTSTRAP_HOSTNAME: node-0
      CL_VALIDATOR_PUBLIC_KEY: $CL_VALIDATOR_PUBLIC_KEY
      CL_VALIDATOR_PRIVATE_KEY: $CL_VALIDATOR_PRIVATE_KEY
      CL_GRPC_SOCKET: /root/.casperlabs/sockets/.casper-node.sock
    entrypoint:
      - sh
      - -c
      - chmod +x ./start-node.sh && ./start-node.sh
    depends_on:
      # Need the gRPC socket file to be created before we start the node.
      - execution-engine
      """
        container = self.client.containers.run(
            image=f'casperlabs/node:{version}',
            user='root',
            detach=True,
            name=f'node-{self.node_number}',
            hostname=f'node-{self.node_number}',
            network=self.network.name,
            volumes=[
                'socketvolume:/root/.casperlabs/socket',
                '../../docker/template/start-node.sh:/opt/docker/start-node.sh',
            ],
            environment={
                'BOOTSTRAP_HOSTNAME': "node-0",
                'CL_VALIDATOR_PUBLIC_KEY': CL_VALIDATOR_PUBLIC_KEY,
                'CL_VALIDATOR_PRIVATE_KEY': CL_VALIDATOR_PRIVATE_KEY,
                'CL_GRPC_SOCKET': "/root/.casperlabs/sockets/.casper-node.sock"
            },
        )
        return container

    def _run_ee_container(self, version: str):
        """
            image: casperlabs/execution-engine:${CL_VERSION}
            container_name: execution-engine-${NODE_NUMBER}
            hostname: execution-engine-${NODE_NUMBER}
            volumes:
              - socketvolume:/opt/docker/.casperlabs/sockets
            networks:
              - casperlabs
            command:
              - .casperlabs/sockets/.casper-node.sock
        """
        container = self.client.containers.run(
            image=f'casperlabs/execution-engine:{version}',
            user='root',
            detach=True,
            name=f'execution-engine-{self.node_number}',
            hostname=f'execution-enginer-{self.node_number}',
            network=self.network.name,
            volumes=[
                'socketvolume:/root/.casperlabs/socket',
            ],
            command='.casperlabs/sockets/.casper-node.sock'
        )
        return container

    def ee_logs(self):
        return self.ee_container.logs().decode('UTF-8')

    def node_logs(self):
        return self.node_container.logs().decode('UTF-8')

    def invoke_node_client(self):
       pass


if __name__ == '__main__':
    client = docker.from_env()
    print([(net.name, net.id) for net in client.networks.list()])
    np = NodePair(client, 0)
    # print(np)
    # print(np.network)
    print([(net.name, net.id) for net in client.networks.list()])

