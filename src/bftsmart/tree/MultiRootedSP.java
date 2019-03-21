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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
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

public class MultiRootedSP {
	
	private Logger logger; 

	private ServerCommunicationSystem commS = null;
	private ServerViewController SVController = null;
	private ReentrantLock lock = new ReentrantLock();

	private int replicaId;
	private int currentLeader = -1;
	private boolean finish = false;
	
	private HashMap<Integer, Set<Integer>> viewChildren;
	private HashMap<Integer, Integer> viewParent;
	private HashMap<Integer, Queue<Integer>> viewUnexplored;
	
	private int n;
	private int f;
	
	// Constructor.
	public MultiRootedSP(
			ServerCommunicationSystem commS, 
			ServerViewController SVController, 
			int leader) {
		this.logger = LoggerFactory.getLogger(this.getClass());
		
		this.commS = commS;
		this.SVController = SVController;
		this.replicaId = SVController.getStaticConf().getProcessId();
		this.f = this.SVController.getCurrentViewF();
		this.n = this.SVController.getCurrentViewN();
		
		this.viewChildren = new HashMap<>();
		this.viewParent = new HashMap<>();
		this.viewUnexplored = new HashMap<>();
		
		this.currentLeader = leader;

		//create initial view. Startup
		int [] allNeighbors = SVController.getCurrentViewAcceptors();
		
		for (int view = 0; view < allNeighbors.length; view++) {
			this.viewChildren.put(view, new HashSet<Integer>());
			this.viewUnexplored.put(view, new LinkedList<Integer>());
			this.viewParent.put(view, -1);
			
			Integer[] neighbors = Arrays.stream(allNeighbors).boxed().toArray(Integer[]::new);
			Collections.shuffle(Arrays.asList(neighbors), new Random());
				
			for (int j = 0; j < neighbors.length; j++) {
				if(view != neighbors[j] 
						&& this.viewUnexplored.get(view).size() < (f+1)) {
						this.viewUnexplored.get(view).add(neighbors[j]);
					}
				else  continue;
			}
		}
		
	}

	

	public boolean initProtocol(int viewTag) {
		if (this.replicaId == viewTag 
				&& this.viewParent.get(viewTag) == -1) {
			viewParent.put(replicaId,replicaId);
			logger.debug("Spanning Tree initialized by root viewTag:{}", viewTag);
			explore(viewTag);
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

	private void explore(int viewTag) {
		lock.lock();
		if (!this.viewUnexplored.get(viewTag).isEmpty()) {
			TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.M);
			int toSend = this.viewUnexplored.get(viewTag).poll();
			logger.trace("Sending M message to: {}", toSend);
			commS.send(new int[] { toSend }, signMessage(tm));
		} else {
			if (this.viewParent.get(viewTag) != this.replicaId) {
				TreeMessage tm = new TreeMessage(viewTag, TreeOperationType.PARENT);
				commS.send(new int[] { this.viewParent.get(viewTag) }, signMessage(tm));
				receivedFinished(new TreeMessage(viewTag, TreeOperationType.FINISHED));
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
			initProtocol(msg.getViewTag());
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
			//createStaticTree();
			break;
		case NOOP:
		default:
			logger.info("NOOP or default message catched...");
			break;
		}
	}

	private void receivedM(TreeMessage msg) {
		if (this.viewParent.get(msg.getViewTag()) == -1) {
			lock.lock();
			this.viewParent.put(msg.getViewTag(), msg.getSender());
			this.viewUnexplored.get(msg.getViewTag()).remove(msg.getSender());
			lock.unlock();
			logger.trace("Defining {} as my patent.", msg.getSender());
			explore(msg.getViewTag());
		} else {
			TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.ALREADY);
			commS.send(new int[] { msg.getSender() }, signMessage(tm));
			lock.lock();
			this.viewUnexplored.get(msg.getViewTag()).remove(msg.getSender());
			lock.unlock();
			logger.trace("Sending ALREADY msg to: {}", msg.getSender());
		}
	}

	private void receivedAlready(TreeMessage msg) {
		explore(msg.getViewTag());
	}

	private void receivedParent(TreeMessage msg) {
		System.out.println("Adding " + msg.getSender() + " as a child.");
		lock.lock();
		if (!this.viewChildren.get(msg.getViewTag()).contains(msg.getSender()))
			this.viewChildren.get(msg.getViewTag()).add(msg.getSender());
		lock.unlock();
		explore(msg.getViewTag());
	}

	private void receivedFinished(TreeMessage msg) {
		if (!this.finish) {
			System.out.println("Finished spanning tree, SpanningTree:\n" + toString());
			this.finish = true;
			if (msg.getViewTag() != this.viewParent.get(msg.getViewTag())) {
				TreeMessage tm = new TreeMessage(msg.getViewTag(), TreeOperationType.FINISHED);
				commS.send(new int[] { this.viewParent.get(msg.getViewTag()) }, signMessage(tm));
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


	public int getParent() {
		return this.viewParent.get(replicaId);
	}

	public boolean getFinish() {
		return this.finish;
	}
	
	public void forwardTreeMessage(ForwardTree msg) {
		ConsensusMessage cm = msg.getConsensusMessage();
		
		switch (msg.getDirection()) {
		case UP:
			if(this.viewParent.get(replicaId) != replicaId) {
				logger.info("Forwarding Tree message UP, "
						+ "fwdT.from:{} -> to:{}, cm.sender:{}, cm.type:{}", 
						new Object[] {msg.getSender(), this.viewParent.get(msg.getViewTag()), cm.getSender(), cm.getType()}
						);	
				ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.UP, msg.getViewTag()); 
				commS.send(new int[] { this.viewParent.get(msg.getViewTag()) }, fwdTree);
			}
			break;
		case DOWN:
			if (this.viewChildren.get(msg.getViewTag()).isEmpty()) {
				logger.debug("I have no children.");
				return;
			}
			ForwardTree fwdTree = new ForwardTree(replicaId, cm, Direction.DOWN,msg.getViewTag());
			Iterator<Integer> it = this.viewChildren.get(msg.getViewTag()).iterator();
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
	
	@Override
	public String toString() {
		
		String appended = "";
		logger.info("All Views: {}", SVController.getCurrentViewAcceptors());
		
		Iterator<Integer> it = this.viewChildren.keySet().iterator();
		while (it.hasNext()) {
			Integer view = (Integer) it.next();
			logger.info("View: {}", view);
			logger.info("\tChildren: {}", this.viewChildren.get(view));
			logger.info("\tParent: {} ", this.viewParent.get(view));
			logger.info("\tUnexplored: {} ", this.viewUnexplored.get(view));
			
		}
		return appended;
	}
}
