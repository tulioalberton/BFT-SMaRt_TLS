/**
Copyright (c) 2019-2019 Tulio Alberton Ribeiro.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.demo.tree;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.tom.ServiceProxy;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tree.messages.TreeMessage;

/**
 * Example client that updates a BFT replicated service (a counter).
 */
public class TreeClient_1Request {

	private static Logger logger;
	
	public static int clientId = 0;

	@SuppressWarnings("static-access")
	public static void main(String[] args) throws IOException {
		
		logger = LoggerFactory.getLogger(TreeClient_1Request.class);
		
		if (args.length < 1) {
			logger.info("Usage: ... TreeClient <client id>");
			System.exit(-1);
		}
		
		clientId = Integer.parseInt(args[0]);
		
		Client client = new TreeClient_1Request.Client(clientId);
		client.start();
		logger.info("Clients done.");
	}

	static class Client extends Thread {

		int clientId;
		TreeMessage treeMessage;
		ServiceProxy proxy;
		
		public Client(int clientId) {
			super("Client " + clientId);
			this.clientId = clientId;
			this.proxy = new ServiceProxy(clientId);
		}

		public void run() {
			
			logger.info("Executing init tree experiment.");
			
			byte[] reply = proxy.invoke(new byte[0], TOMMessageType.REQUEST_LEADER);
			
			System.out.println("Reply: " + reply);
			
			proxy.close();
		}
	}
}