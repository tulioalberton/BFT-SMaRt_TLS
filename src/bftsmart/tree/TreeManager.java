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
	private boolean leaf = false;
	private List<Integer> children;
	private Queue<Integer> unexplored;
	private boolean finish = false;

	private int n;
	private int f;
	// private boolean canIExplore = false;

	// Constructor.
	public TreeManager(ServerCommunicationSystem commS, ServerViewController SVController, int leader) {
		this.logger = LoggerFactory.getLogger(this.getClass());

		this.commS = commS;
		this.SVController = SVController;
		this.replicaId = SVController.getStaticConf().getProcessId();
		this.parent = -1;
		this.currentLeader = leader;
		this.unexplored = new LinkedList<Integer>();
		this.f = this.SVController.getCurrentViewF();
		this.n = this.SVController.getCurrentViewN();

		int[] neighbors = SVController.getCurrentViewOtherAcceptors();
		/*Integer[] neighbors = Arrays.stream(SVController.getCurrentViewOtherAcceptors()).boxed()
				.toArray(Integer[]::new);*/
		// Collections.shuffle(Arrays.asList(neighbors), new Random());
		for (int i = 0; i < neighbors.length; i++) {
			this.unexplored.add(neighbors[i]);
		}
		this.children = new LinkedList<Integer>();
	}

	public boolean initProtocol() {
		if (this.replicaId == this.currentLeader && parent == -1) {
			this.parent = replicaId;
			logger.debug("Spanning Tree initialized by root.\n{}", toString());

			explore();
			return true;
		} else {
			return false;
		}
	}

	private void explore() {

		for (int i = 0; i < (this.f); i++) {
			if (!this.unexplored.isEmpty()) {
				TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.DISCOVER, currentLeader);
				lock.lock();
				int toSend = this.unexplored.poll();
				lock.unlock();

				if (this.children.size() >= (this.f + 1)) {
					logger.warn("I have >= f+1 children. ");
					try {
						Thread.sleep(150);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} else if (this.children.size() < (this.f + 1)) {

					logger.debug("Sending DISCOVER message to: {}.", toSend);
					commS.send(new int[] { toSend }, signMessage(tm));
				}

			} else {
				if (this.parent != this.replicaId) {
					TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.FINISHED, currentLeader);
					commS.send(new int[] { this.parent }, signMessage(tm));
					receivedFinished(new TreeMessage(this.replicaId, TreeOperationType.FINISHED, currentLeader));
				}
			}
		}

	}

	public synchronized void treatMessages(TreeMessage msg) {

		logger.debug("Received TreeMessage, " + "Type:{}, Sender:{}.", msg.getTreeOperationType(), msg.getSender());
		if (!verifySignature(msg)) {
			return;
		}

		switch (msg.getTreeOperationType()) {
		case INIT:
			initProtocol();
			break;
		case DISCOVER:
			receivedDiscover(msg);
			break;
		case ALREADY:
			receivedAlready(msg);
			break;
		case PARENT:
			receivedParent(msg);
			break;
		case FINISHED:
			receivedFinished(msg);
			break;
		case RECONFIG:
			// shall clean the data structures and init the protocol, is just this?
			SVController.getStaticConf().setSpanningTree(false);
			logger.debug("RECONFIG message not catched...");
			break;
		case STATUS:
			logger.info("TREE STATUS: \n {}", toString());
			break;
		case STATIC_TREE:
			createStaticTree();
			break;
		case NOOP:
		default:
			logger.warn("NOOP or default message catched...");
			break;
		}
	}

	private void receivedDiscover(TreeMessage msg) {
		lock.lock();
		if (this.parent == -1) {

			this.parent = msg.getSender();
			this.unexplored.remove(msg.getSender());
			lock.unlock();
			logger.trace("Defining {} as my parent.", msg.getSender());
			TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.PARENT, currentLeader);
			commS.send(new int[] { msg.getSender() }, signMessage(tm));
			
			try {
				Thread.sleep(150);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			explore();
		} else {
			TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.ALREADY, currentLeader);
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
		lock.lock();
		if (!this.children.contains(msg.getSender())) {
			this.children.add(msg.getSender());
			logger.debug("Adding {} as a child. Children:{}", 
					msg.getSender(), this.children.toArray());
		}
		lock.unlock();

		explore();
	}

	private void receivedFinished(TreeMessage msg) {
		if (!this.finish) {
			logger.info("Finished spanning tree, SpanningTree:\n" + toString());
			this.finish = true;
			SVController.getStaticConf().setSpanningTree(true);
			if (replicaId != parent) {
				TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.FINISHED, currentLeader);
				commS.send(new int[] { parent }, signMessage(tm));
			}
		}
		if (this.children.isEmpty())
			this.leaf = true;
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
		if (TOMUtil.verifySignature(SVController.getStaticConf().getPublicKey(msg.getSender()),
				msg.toString().getBytes(), msg.getSignature())) {
			// logger.trace("Message was successfully verified.");
			return true;
		} else {
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

	public boolean getAmILeaf() {
		return this.leaf;
	}

	public void forwardTreeMessage(ForwardTree msg) {
		ConsensusMessage cm = msg.getConsensusMessage();

		switch (msg.getDirection()) {
		case UP:
			if (this.parent != replicaId) {
				logger.debug("Forwarding Tree message UP, " + "fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}",
						new Object[] { msg.getSender(), parent, cm.getSender(), cm.getType() });
				ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.UP, msg.getViewTag());
				commS.send(new int[] { this.parent }, fwdTree);
			} else if (this.parent == replicaId) {
				ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.DOWN, msg.getViewTag());

				Iterator<Integer> it = this.children.iterator();
				while (it.hasNext()) {
					Integer child = (Integer) it.next();
					if (child != msg.getSender()) {
						logger.debug(
								"Forwarding Tree message DOWN (LEADER CURVE), fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}",
								new Object[] { fwdTree.getSender(), child, cm.getSender(), cm.getType() });
						commS.send(new int[] { child }, fwdTree);
						// commS.send(new int[] { child }, cm);
					}
				}
			}
			break;
		case DOWN:
			if (this.children.isEmpty()) {
				logger.trace("I have no children. Consuming message.");
				return;
			}
			ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.DOWN, msg.getViewTag());
			Iterator<Integer> it = this.children.iterator();
			while (it.hasNext()) {
				Integer child = (Integer) it.next();
				logger.debug("Forwarding Tree message DOWN, fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}",
						new Object[] { fwdTree.getSender(), child, cm.getSender(), cm.getType() });
				commS.send(new int[] { child }, fwdTree);
			}
			break;
		/*
		 * case LEAFS:
		 * 
		 * switch (replicaId) {
		 * 
		 * case 3: case 4: logger.debug("Sending Consensus message to leafs. 5 and 6");
		 * commS.send(new int[] {2}, new ForwardTree(replicaId, cm, Direction.DOWN,
		 * msg.getViewTag())); //commS.send(new int[] {5, 6}, cm); break;
		 * 
		 * case 5: case 6: logger.debug("Sending Consensus message to leafs. 3 and 4");
		 * commS.send(new int[] {1}, new ForwardTree(replicaId, cm, Direction.DOWN,
		 * msg.getViewTag())); //commS.send(new int[] {3, 4}, cm); break;
		 * 
		 * default: break; }
		 * 
		 * break;
		 */
		default:
			logger.debug("Direction not defined. Not sending message.");
			break;
		}
	}

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
				this.children.add(4);
				break;
			case 2:
				this.parent = 0;
				this.children.add(5);
				this.children.add(6);
				break;
			case 3:
				this.parent = 1;
				this.leaf = true;
				break;
			case 4:
				this.parent = 1;
				this.leaf = true;
				break;
			case 5:
				this.parent = 2;
				this.leaf = true;
				break;
			case 6:
				this.parent = 2;
				this.leaf = true;
				break;
			default:
				break;
			}
			this.finish = true;
			logger.debug("Stacic tree created. \n {}", toString());
		}
	}

	@Override
	public String toString() {
		String appended = "\nReplicaId: " + replicaId + "";
		appended += "\nParent: " + parent + "\n";
		if (!this.children.isEmpty()) {
			appended += "Children: [";
			Iterator<Integer> it = this.children.iterator();
			while (it.hasNext()) {
				appended += " " + (Integer) it.next() + " ";
			}
			appended += "]";
		} else {
			appended += "I am a leaf.\n";
		}

		return appended;
	}
}
