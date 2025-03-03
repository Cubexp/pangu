/*
 * Copyright (c) 2011-2018, Meituan Dianping. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dianping.cat.message.queue;

import com.dianping.cat.message.spi.MessageQueue;
import com.dianping.cat.message.spi.MessageTree;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class PriorityMessageQueue implements MessageQueue {
    private final BlockingQueue<MessageTree> highQueue;
    private final BlockingQueue<MessageTree> normalQueue;

    public PriorityMessageQueue(int size) {
        highQueue = new ArrayBlockingQueue<MessageTree>(size / 2);
        normalQueue = new ArrayBlockingQueue<MessageTree>(size);
    }

    @Override
    public boolean offer(MessageTree tree) {
        if (tree.canDiscard()) {
            return normalQueue.offer(tree);
        } else {
            return highQueue.offer(tree);
        }
    }

    @Override
    public MessageTree peek() {
        MessageTree tree = highQueue.peek();

        if (tree == null) {
            tree = normalQueue.peek();
        }
        return tree;
    }

    @Override
    public MessageTree poll() {
        MessageTree tree = highQueue.poll();

        if (tree == null) {
            try {
                tree = normalQueue.poll(5, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        }

        return tree;
    }

    @Override
    public int size() {
        return normalQueue.size() + highQueue.size();
    }
}
