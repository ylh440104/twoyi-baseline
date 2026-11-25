// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use std::cell::Cell;
use std::io;
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::sync::mpsc::{self, Receiver, SyncSender};

pub struct InputQueue<T> {
    sender: Cell<Option<SyncSender<T>>>,
    peer: UnixStream,
}

impl<T> InputQueue<T> {
    pub fn new(peer: UnixStream, capacity: usize) -> (Self, Receiver<T>) {
        let (sender, receiver) = mpsc::sync_channel(capacity);
        (Self { sender: Cell::new(Some(sender)), peer }, receiver)
    }

    pub fn enqueue(&self, event: T) {
        if let Some(sender) = self.sender.take() {
            if sender.try_send(event).is_ok() {
                self.sender.set(Some(sender));
                return;
            }
            // A truncated gesture or key sequence is invalid. Close the stream
            // so the guest can reconnect and start with a clean input state.
            let _ = self.peer.shutdown(Shutdown::Both);
        }
    }
}

impl<T> Drop for InputQueue<T> {
    fn drop(&mut self) {
        // Retire the connection even while the writer owns a cloned descriptor.
        let _ = self.peer.shutdown(Shutdown::Both);
    }
}

/// Stop when the producer is replaced or the guest no longer accepts events.
pub fn forward_events<T>(events: Receiver<T>, mut write: impl FnMut(T) -> io::Result<()>) {
    while let Ok(event) = events.recv() {
        if write(event).is_err() {
            break;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::channel;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn producer_disconnect_drains_then_exits() {
        let (tx, rx) = channel();
        let (done, result) = channel();
        tx.send(1).unwrap();
        tx.send(2).unwrap();
        drop(tx);
        thread::spawn(move || {
            let mut received = Vec::new();
            forward_events(rx, |event| { received.push(event); Ok(()) });
            done.send(received).unwrap();
        });
        assert_eq!(result.recv_timeout(Duration::from_secs(2)).unwrap(), vec![1, 2]);
    }

    #[test]
    fn local_peer_receives_complete_frames_in_order() {
        use std::io::{Read, Write};
        use std::os::unix::net::UnixStream;

        let (mut peer, mut writer) = UnixStream::pair().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let (tx, rx) = channel();
        let frames = [[1_u8, 2, 3, 4], [5, 6, 7, 8]];
        for frame in frames {
            tx.send(frame).unwrap();
        }
        drop(tx);
        let worker = thread::spawn(move || {
            forward_events(rx, |frame| writer.write_all(&frame));
        });
        let mut payload = [0_u8; 8];
        peer.read_exact(&mut payload).unwrap();
        assert_eq!(payload, [1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(peer.read(&mut payload).unwrap(), 0);
        worker.join().unwrap();
    }

    #[test]
    fn replacement_closes_old_connection_with_writer_still_alive() {
        use std::io::{Read, Write};

        let (mut old_guest, mut old_writer) = UnixStream::pair().unwrap();
        old_guest.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let (old_queue, _old_rx) = InputQueue::<u8>::new(old_writer.try_clone().unwrap(), 1);
        let (mut new_guest, new_writer) = UnixStream::pair().unwrap();
        new_guest.set_read_timeout(Some(Duration::from_secs(2))).unwrap();

        let (new_queue, new_rx) = InputQueue::new(new_writer.try_clone().unwrap(), 1);
        drop(old_queue);

        let mut byte = [0];
        assert_eq!(old_guest.read(&mut byte).unwrap(), 0);
        assert!(old_writer.write_all(&[1]).is_err());
        new_queue.enqueue(2_u8);
        let mut new_writer = new_writer;
        new_writer.write_all(&[new_rx.try_recv().unwrap()]).unwrap();
        new_guest.read_exact(&mut byte).unwrap();
        assert_eq!(byte, [2]);
    }

    #[test]
    fn full_queue_closes_socket_and_rejects_subsequent_events() {
        use std::io::Read;
        use std::sync::mpsc::TryRecvError;

        let (mut guest, writer) = UnixStream::pair().unwrap();
        guest.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let (queue, receiver) = InputQueue::new(writer.try_clone().unwrap(), 1);
        queue.enqueue(1);
        queue.enqueue(2);
        assert_eq!(guest.read(&mut [0]).unwrap(), 0);
        assert_eq!(receiver.try_recv().unwrap(), 1);
        queue.enqueue(3);
        assert_eq!(receiver.try_recv(), Err(TryRecvError::Disconnected));
    }

    #[test]
    fn broken_socket_exits_without_waiting_for_producer() {
        let (tx, rx) = channel();
        let (done, result) = channel();
        tx.send(1).unwrap();
        tx.send(2).unwrap();
        thread::spawn(move || {
            let mut calls = 0;
            forward_events(rx, |_| {
                calls += 1;
                Err(io::Error::new(io::ErrorKind::BrokenPipe, "guest disconnected"))
            });
            done.send(calls).unwrap();
        });
        assert_eq!(result.recv_timeout(Duration::from_secs(2)).unwrap(), 1);
        assert!(tx.send(3).is_err());
    }
}
