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

package bftsmart.tree;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.tom.util.TOMUtil;
import bftsmart.tree.messages.ForwardTree;
import bftsmart.tree.messages.ForwardTree.Direction;
import bftsmart.tree.messages.TreeMessage;
import bftsmart.tree.messages.TreeMessage.TreeOperationType;

public class TreeManager {
	
	private Logger logger; 

	private ServerCommunicationSystem commS = null;
	private ServerViewController SVController = null;
	private ReentrantLock lock = new ReentrantLock();

	private int replicaId;
	private int currentLeader = -1;
	private int parent;
	private List<Integer> children;
	private Queue<Integer> unexplored;
	private boolean finish = false;

	// Constructor.
	public TreeManager(ServerCommunicationSystem commS, ServerViewController SVController, int leader) {
		this.logger = LoggerFactory.getLogger(this.getClass());
		
		this.commS = commS;
		this.SVController = SVController;
		this.replicaId = SVController.getStaticConf().getProcessId();
		this.parent = -1;
		this.currentLeader = leader;
		this.unexplored = new LinkedList<Integer>();

		// int[] neighbors = SVController.getCurrentViewOtherAcceptors();
		Integer[] neighbors = Arrays.stream(SVController.getCurrentViewOtherAcceptors()).boxed()
				.toArray(Integer[]::new);
		Collections.shuffle(Arrays.asList(neighbors), new Random());
		for (int i = 0; i < neighbors.length; i++) {
			this.unexplored.add(neighbors[i]);
		}
		this.children = new LinkedList<Integer>();
	}

	

	public boolean initProtocol() {
		if (this.replicaId == this.currentLeader && parent == -1) {
			this.parent = replicaId;
			logger.debug("Spanning Tree initialized by root.\n{}" , toString());
			explore();
			/*
			 * TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.M); while
			 * (!this.unexplored.isEmpty()) { int toSend = this.unexplored.poll();
			 * System.out.println("Sending M message to: " + toSend); commS.send(new int[] {
			 * toSend }, signedMessage(tm)); Random rand = new Random(); try {
			 * Thread.sleep(rand.nextInt(10)); } catch (InterruptedException e) { // TODO
			 * Auto-generated catch block e.printStackTrace(); } }
			 */
			return true;
		} else {
			return false;
		}
	}

	private void explore() {
		lock.lock();
		if (!this.unexplored.isEmpty()) {
			TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.M,currentLeader);
			int toSend = this.unexplored.poll();
			logger.trace("Sending M message to: {}", toSend);
			commS.send(new int[] { toSend }, signMessage(tm));
		} else {
			if (this.parent != this.replicaId) {
				TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.PARENT,currentLeader);
				commS.send(new int[] { this.parent }, signMessage(tm));
				receivedFinished(new TreeMessage(this.replicaId, TreeOperationType.FINISHED,currentLeader));
			}
		}
		lock.unlock();
	}
	
	public synchronized void treatMessages(TreeMessage msg) {
		
		logger.debug("Received TreeMessage, "
				+ "Type:{}, Sender:{}.", msg.getTreeOperationType(), msg.getSender());
		if (!verifySignature(msg)) {
			return;
		}
		
		switch (msg.getTreeOperationType()) {
		case INIT:
			initProtocol();
			break;
		case M:
			receivedM(msg);
			break;
		case ALREADY:
			receivedAlready(msg);
			break;
		case PARENT:
			receivedParent(msg);
			break;
		case FINISHED:
			receivedParent(msg);
			break;
		case RECONFIG:
			// shall clean the data structures and init the protocol, is just this?
			logger.info("RECONFIG message not catched...");
			break;
		case STATIC_TREE:
			createStaticTree();
			break;
		case NOOP:
		default:
			logger.info("NOOP or default message catched...");
			break;
		}
	}

	private void receivedM(TreeMessage msg) {
		if (this.parent == -1) {
			lock.lock();
			this.parent = msg.getSender();
			this.unexplored.remove(msg.getSender());
			lock.unlock();
			logger.trace("Defining {} as my patent.", msg.getSender());
			explore();
		} else {
			TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.ALREADY,currentLeader);
			commS.send(new int[] { msg.getSender() }, signMessage(tm));
			lock.lock();
			this.unexplored.remove(msg.getSender());
			lock.unlock();
			logger.trace("Sending ALREADY msg to: {}", msg.getSender());
		}
	}

	private void receivedAlready(TreeMessage msg) {
		explore();
	}

	private void receivedParent(TreeMessage msg) {
		System.out.println("Adding " + msg.getSender() + " as a child.");
		lock.lock();
		if (!this.children.contains(msg.getSender()))
			this.children.add(msg.getSender());
		lock.unlock();
		explore();
	}

	private void receivedFinished(TreeMessage msg) {
		if (!this.finish) {
			System.out.println("Finished spanning tree, SpanningTree:\n" + toString());
			this.finish = true;
			if (replicaId != parent) {
				TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.FINISHED,currentLeader);
				commS.send(new int[] { parent }, signMessage(tm));
			}
		}
	}

	private TreeMessage signMessage(TreeMessage tm) {
		Signature eng;
		try {
			eng = TOMUtil.getSigEngine();
			eng.initSign(SVController.getStaticConf().getPrivateKey());
			eng.update(tm.toString().getBytes());
			tm.setSignature(eng.sign());
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
			e.printStackTrace();
		}
		return tm;
	}
	
	private boolean verifySignature(TreeMessage msg) {
		if(TOMUtil.verifySignature(SVController.getStaticConf().getPublicKey(msg.getSender()), 
				msg.toString().getBytes(), msg.getSignature())) {
			logger.debug("Message was successfully verified.");
			return true;
		}else {			
			logger.warn("Signature verification NOT succeed.");
			return false;
		}
	}

	public List<Integer> getChildren() {
		return this.children;
	}

	public int getParent() {
		return this.parent;
	}

	public boolean getFinish() {
		return this.finish;
	}

	/*public void forwardToParent(ForwardTree msg) {
		if(this.parent != replicaId)
			commS.send(new int[] { this.parent }, msg);
	}*/
	
	public void forwardTreeMessage(ForwardTree msg) {
		ConsensusMessage cm = msg.getConsensusMessage();
		
		switch (msg.getDirection()) {
		case UP:
			if(this.parent != replicaId) {
				logger.info("Forwarding Tree message UP, "
						+ "fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}", 
						new Object[] {msg.getSender(), parent, cm.getSender(), cm.getType()}
						);	
				ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.UP, msg.getViewTag()); 
				commS.send(new int[] { this.parent }, fwdTree);
			}
			break;
		case DOWN:
			if (this.children.isEmpty()) {
				logger.debug("I have no children.");
				return;
			}
			ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.DOWN, msg.getViewTag());
			Iterator<Integer> it = this.children.iterator();
			while (it.hasNext()) {
				Integer child = (Integer) it.next();
				logger.info("Forwarding Tree message DOWN, fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}", 
						new Object[] {fwdTree.getSender(), child, cm.getSender(), cm.getType()}
						);	
				commS.send(new int[] { child }, fwdTree);
			}
			break;
		default:
			logger.info("Direction not defined. Not sending message.");
			break;
		}
	}
	/*public void forwardToChildren(ForwardTree msg) {
		ConsensusMessage cm = msg.getConsensusMessage();
		
		if (this.children.isEmpty()) {
			logger.debug("I have no children.");
			return;
		}
		ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.DOWN); 
		
		Signature eng;
		try {
			eng = TOMUtil.getSigEngine();
			eng.initSign(SVController.getStaticConf().getPrivateKey());
			eng.update(fwdTree.toString().getBytes());
			fwdTree.setSignature(eng.sign());
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
			e.printStackTrace();
		}
				
		Iterator<Integer> it = this.children.iterator();
		while (it.hasNext()) {
			Integer child = (Integer) it.next();
			logger.info("Forwarding Tree message: fwdT.from:{} -> to{}:, cm.sender:{}, cm.type:{}", 
					new Object[] {msg.getSender(), child, cm.getSender(), cm.getType()}
					);	
			commS.send(new int[] { child }, fwdTree);
		}
	}*/

	public synchronized void createStaticTree() {
		if (!this.finish) {
			switch (replicaId) {
			case 0:
				this.parent = 0;
				this.children.add(1);
				this.children.add(2);
				break;
			case 1:
				this.parent = 0;
				this.children.add(3);
				//this.children.add(4);
				break;
			case 2:
				this.parent = 0;
				//this.children.add(5);
				//this.children.add(6);
				break;
			case 3:
				this.parent = 1;
				break;
			case 4:
				this.parent = 1;
				break;
			case 5:
				this.parent = 2;
				break;
			case 6:
				this.parent = 2;
				break;
			default:
				break;
			}
			this.finish = true;
			logger.debug("Stacic tree created. \n {}" ,toString());
		}
	}
	
	@Override
	public String toString() {
		String appended = "\nReplicaId: " + replicaId + "";
		appended += "\nParent: " + parent + "\n";
		if (!this.children.isEmpty()) {
			appended += "Children: \n";
			Iterator<Integer> it = this.children.iterator();
			while (it.hasNext()) {
				appended += "\t " + (Integer) it.next() + " \n";
			}
		}else {
			appended += "I am a leaf.\n";
		}

		return appended;
	}
}
