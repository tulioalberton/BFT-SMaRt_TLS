/**
Copyright (c) 2019-2019 Tulio Alberton Ribeiro
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
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.tom.ServiceProxy;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.TOMUtil;
import bftsmart.tree.messages.TreeMessage;
import bftsmart.tree.messages.TreeMessage.TreeOperationType;

/**
 * Example client that updates a BFT replicated service (a counter).
 */
public class TreeInit {

	private static Logger logger;
	
	public static int clientId = 0;
	public static TreeOperationType treeOperation = TreeOperationType.NOOP;
	public static TreeMessage treeMessage;

	@SuppressWarnings("static-access")
	public static void main(String[] args) throws IOException {
		
		logger = LoggerFactory.getLogger(TreeInit.class);
		
		if (args.length < 2) {
			logger.info("Usage: ... TreeClient <client id> <tree Operation INIT|RECONFIG>");
			System.exit(-1);
		}
		
		clientId = Integer.parseInt(args[0]);
		switch (args[1].toUpperCase()) {
		case "INIT":
			treeOperation = TreeOperationType.INIT;	
			break;
		case "RECONFIG":
			treeOperation = TreeOperationType.RECONFIG;	
			break;
		default:
			treeOperation = TreeOperationType.NOOP;
			logger.info("Usage: ... TreeClient <client id> <tree Operation INIT|RECONFIG>");
			logger.info("Catched NOOP...");
			System.exit(-1);
			break;
		}
		
		treeMessage = new TreeMessage(clientId, treeOperation);
		
		Client client = new TreeInit.Client(clientId, treeMessage);
		client.start();
		logger.info("Clients done.");
	}

	static class Client extends Thread {

		int clientId;
		TreeMessage treeMessage;
		ServiceProxy proxy;
		
		public Client(int clientId, TreeMessage treeMessage) {
			super("Client " + clientId);
			this.clientId = clientId;
			this.treeMessage = treeMessage;
			this.proxy = new ServiceProxy(clientId);
		}

		public void run() {
			/**
			 * Sign message first.
			 */
			Signature eng;
			try {
				eng = TOMUtil.getSigEngine();
				eng.initSign(proxy.getViewManager().getStaticConf().getPrivateKey());
				eng.update(this.treeMessage.toString().getBytes());
				this.treeMessage.setSignature(eng.sign());
			} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
				e.printStackTrace();
			}
			
			
			logger.info("Executing init tree experiment.");
			
			byte[] reply = proxy.invoke(TOMUtil.getBytes(this.treeMessage), TOMMessageType.TREE_INIT);
			
			System.out.println("Reply: " + reply);
			proxy.close();
		}

	}
}