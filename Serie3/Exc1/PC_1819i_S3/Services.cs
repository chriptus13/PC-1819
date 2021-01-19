using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using JsonEchoServer;
using Newtonsoft.Json.Linq;

namespace PC_1819i_S3
{
    public class Services
    {
        private readonly object _mon = new object();

        private readonly Dictionary<string, MessageQueue<JObject>> _queues =
            new Dictionary<string, MessageQueue<JObject>>();

        private volatile bool _shutdown = false, _end = false;

        private static Response
            _shutdownRes = new Response() {Status = 503},
            _noPathRes = new Response() {Status = 404};

        public Task<Response> Create(string path)
        {
            if (_shutdown) return Task.FromResult(_shutdownRes);
            lock (_mon)
            {
                if (!_queues.ContainsKey(path))
                    _queues.Add(path, new MessageQueue<JObject>());

                return Task.FromResult(new Response() {Status = 200});
            }
        }

        public Task<Response> Send(string path, JObject msg)
        {
            if (_shutdown) return Task.FromResult(_shutdownRes);
            lock (_mon)
            {
                if (!_queues.TryGetValue(path, out var queue)) return Task.FromResult(_noPathRes);
                return queue.Send(msg)
                    .ContinueWith(task =>
                        task.IsCompletedSuccessfully ? new Response() {Status = 200} : new Response() {Status = 500});
            }
        }

        public Task<Response> Receive(string path, TimeSpan timeout)
        {
            if (_shutdown) return Task.FromResult(_shutdownRes);
            lock (_mon)
            {
                if (!_queues.TryGetValue(path, out var queue)) return Task.FromResult(_noPathRes);
                return queue.Receive(timeout)
                    .ContinueWith(task =>
                        task.IsCanceled
                            ? new Response() {Status = 204}
                            : (task.IsCompletedSuccessfully
                                ? new Response() {Status = 200, Payload = task.Result}
                                : new Response() {Status = 500})
                    );
            }
        }

        public Task<Response> Shutdown(TimeSpan timeout)
        {
            lock (_mon)
            {
                if (_end) return Task.FromResult(new Response() {Status = 200});
                if (!_shutdown) _shutdown = true;
            }

            if (timeout.TotalMilliseconds <= 0) return Task.FromResult(new Response() {Status = 204});

            var cts = new CancellationTokenSource();
            var ct = cts.Token;
            var opt = new ParallelOptions() {CancellationToken = ct};

            Task<Response> work = Task.Run(() =>
            {
                lock (_mon)
                {
                    Parallel.ForEach(_queues.Values, opt, queue => queue.Clear(ct)); // retirar
                }

                return new Response() {Status = 200};
            }, ct);

            return Task.WhenAny(
                    work,
                    Task.Delay(timeout).ContinueWith(task =>
                    {
                        cts.Cancel();
                        return new Response() {Status = 204};
                    })
                )
                .Unwrap();
        }
    }

    public class MessageQueue<T>
    {
        private readonly object _mon = new object();
        private readonly LinkedList<Consumer> _consumers = new LinkedList<Consumer>();
        private readonly LinkedList<T> _messages = new LinkedList<T>();

        private class Consumer : ConsumerBase<T>
        {
            public CancellationTokenSource Ct { get; set; }
            public CancellationTokenRegistration CancellationTokenRegistration { get; set; }
            public Timer Timer { get; set; }
        }


        public Task Send(T msg)
        {
            lock (_mon)
            {
                while (_consumers.Count != 0)
                {
                    var cons = _consumers.First;
                    if (cons.Value.TryAcquire())
                    {
                        _consumers.RemoveFirst();
                        cons.Value.SetResult(msg);
                        cons.Value.Timer.Dispose();
                        cons.Value.CancellationTokenRegistration.Dispose();
                        return Task.CompletedTask;
                    }
                }

                _messages.AddLast(msg);
                return Task.CompletedTask;
            }
        }

        public Task<T> Receive(TimeSpan timeout)
        {
            lock (_mon)
            {
                if (_messages.Count != 0)
                {
                    var t = _messages.First.Value;
                    _messages.RemoveFirst();
                    return Task.FromResult(t);
                }

                if (timeout.TotalMilliseconds <= 0)
                {
                    TaskCompletionSource<T> tcs = new TaskCompletionSource<T>();
                    tcs.SetCanceled();
                    return tcs.Task;
                }

                var cons = new Consumer();
                var node = _consumers.AddLast(cons);

                cons.Ct = new CancellationTokenSource();
                cons.CancellationTokenRegistration = cons.Ct.Token.Register(CancelDueToCancellationToken, node);
                // Timespan(-1) so no repeats
                cons.Timer = new Timer(CancelDueToTimeout, node, timeout, new TimeSpan(-1));

                return cons.Task;
            }
        }

        public void Clear(CancellationToken ct)
        {
            foreach (var cons in _consumers)
            {
                if (ct.IsCancellationRequested) break;
                cons.Ct.Cancel();
            }
        }

        private void CancelDueToTimeout(object state)
        {
            var node = state as LinkedListNode<Consumer>;

            if (!node.Value.TryAcquire()) return;

            node.Value.CancellationTokenRegistration.Dispose();
            lock (_mon)
            {
                _consumers.Remove(node);
            }

            node.Value.SetCanceled();
        }

        private void CancelDueToCancellationToken(object state)
        {
            var node = state as LinkedListNode<Consumer>;

            if (!node.Value.TryAcquire()) return;

            node.Value.Timer.Dispose();
            lock (_mon)
            {
                _consumers.Remove(node);
            }

            node.Value.SetCanceled();
        }
    }
}