using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using PC_1819i_S3;
using PC_1819i_S3.Logging;
using Serilog;

namespace JsonEchoServer
{
    // To represent a JSON request
    public class Request
    {
        public string Method { get; set; }
        public JObject Headers { get; set; }
        public JObject Payload { get; set; }

        public string Path { get; set; }

        public override string ToString()
        {
            return $"Method: {Method}, Path: {Path}, Headers: {Headers}, Payload: {Payload}";
        }
    }

    // To represent a JSON response
    public class Response
    {
        public int Status { get; set; }
        public JObject Headers { get; set; }
        public JObject Payload { get; set; }
    }

    public class LogQueue<T>
    {
        private readonly object _mon = new object();
        private readonly LinkedList<ConsumerBase<T>> _consumers = new LinkedList<ConsumerBase<T>>();
        private readonly LinkedList<T> _messages = new LinkedList<T>();


        public Task Send(T msg)
        {
            lock (_mon)
            {
                while (_consumers.Count > 0)
                {
                    var cons = _consumers.First;
                    if (cons.Value.TryAcquire())
                    {
                        _consumers.RemoveFirst();
                        cons.Value.SetResult(msg);
                        return Task.CompletedTask;
                    }
                }

                _messages.AddLast(msg);
                return Task.CompletedTask;
            }
        }

        public Task<T> Receive()
        {
            lock (_mon)
            {
                if (_messages.Count != 0)
                {
                    var t = _messages.First.Value;
                    _messages.RemoveFirst();
                    return Task.FromResult(t);
                }

                var cons = new ConsumerBase<T>();
                _consumers.AddLast(cons);

                return cons.Task;
            }
        }
    }


    public class Program
    {
        private static readonly ILog Logger = LogProvider.For<Program>();
        private static readonly LogQueue<string> _logQueue = new LogQueue<string>();
        private static int _counter = 0;

        private const int Port = 8081;
        private static readonly Services Services = new Services();

        private static readonly Dictionary<string, Func<Request, Task<Response>>> Commands =
            new Dictionary<string, Func<Request, Task<Response>>>
            {
                {"CREATE", Create},
                {"SEND", Send},
                {"RECEIVE", Receive},
                {"SHUTDOWN", Shutdown}
            };

        private static readonly Func<string, Response> InvalidResSup = msg => new Response()
        {
            Status = 400,
            Headers = new JObject(new JProperty("StatusMessage", msg))
        };

        private static readonly int Processors = Environment.ProcessorCount;

        private static readonly Semaphore _semaphore = new Semaphore(Processors, Processors);

        private static Thread tLog = new Thread(async () =>
        {
            do
            {
                var t = await _logQueue.Receive();
                Log.Information(t);
            } while (true);
        })
        {
            Priority = ThreadPriority.Lowest
        };

        public static async Task Main(string[] args)
        {
            var log = new LoggerConfiguration()
                .Enrich.WithThreadId()
                .WriteTo.ColoredConsole(
                    outputTemplate:
                    "{Timestamp:HH:mm} [{Level}][th:{ThreadId}] ({Name:l}) {Message}{NewLine}{Exception}")
                .CreateLogger();
            Log.Logger = log;
            tLog.Start();

            var listener = new TcpListener(IPAddress.Loopback, Port);
            listener.Start();
            _logQueue.Send($"Listening on {Port}");
            while (true)
            {
                _semaphore.WaitOne();
                var client = await listener.AcceptTcpClientAsync();
                var id = _counter++;
                _logQueue.Send($"connection accepted with id '{id}'");
                Handle(id, client);
            }
        }

        private static readonly JsonSerializer Serializer = new JsonSerializer();

        private static async void Handle(int id, TcpClient client)
        {
            try
            {
                using (client)
                {
                    var stream = client.GetStream();
                    var reader = new JsonTextReader(new StreamReader(stream))
                    {
                        // To support reading multiple top-level objects
                        SupportMultipleContent = true
                    };
                    var writer = new JsonTextWriter(new StreamWriter(stream));

                    while (true)
                    {
                        try
                        {
                            // to consume any bytes until start of object ('{')
                            do
                            {
                                await reader.ReadAsync();
                                _logQueue.Send($"advanced to {reader.TokenType}");
                            } while (reader.TokenType != JsonToken.StartObject
                                     && reader.TokenType != JsonToken.None);

                            if (reader.TokenType == JsonToken.None)
                            {
                                _logQueue.Send($"[{id}] reached end of input stream, ending.");
                                return;
                            }

                            var json = await JObject.LoadAsync(reader);
                            // to ensure that proper deserialization is possible
                            var req = json.ToObject<Request>();
                            _logQueue.Send(
                                $"\nRequest for client [{id}]\nPath={req.Path}\nMethod={req.Method}\nHeaders={req.Headers}\nPayload={req.Payload}");
                            await ProcessRequest(id, req, writer);
                        }
                        catch (JsonReaderException e)
                        {
                            _logQueue.Send($"Client id:[{id}] Error reading JSON: {e.Message}, continuing");
                            await SendResponse(writer, InvalidResSup("JSON error."));
                            // close the connection because an error may not be recoverable by the reader
                            return;
                        }
                        catch (Exception e)
                        {
                            _logQueue.Send($"Client id:[{id}] Unexpected exception, closing connection {e.Message}");
                            return;
                        }
                    }
                }
            }
            finally
            {
                _semaphore.Release();
            }
        }

        private static async Task SendResponse(JsonWriter writer, Response resp)
        {
            Serializer.Serialize(writer, resp);
            await writer.FlushAsync();
        }

        private static async Task ProcessRequest(int id, Request req, JsonWriter writer)
        {
            try
            {
                var func = Commands[req.Method];
                var resp = await func(req);
                await SendResponse(writer, resp);
                _logQueue.Send(
                    $"\nResponse for client [{id}]\nStatus={resp.Status}\nHeaders={resp.Headers}\nPayload={resp.Payload}");
            }
            catch (KeyNotFoundException)
            {
                await SendResponse(writer, InvalidResSup("Invalid method."));
            }
        }

        private static Task<Response> Create(Request req)
        {
            return Services.Create(req.Path);
        }

        private static Task<Response> Send(Request req)
        {
            return Services.Send(req.Path, req.Payload);
        }

        private static Task<Response> Receive(Request req)
        {
            req.Headers.TryGetValue("timeout", out var ts);
            try
            {
                var t = Convert.ToInt32(ts ?? "0");
                return Services.Receive(req.Path, new TimeSpan(0, 0, 0, 0, t));
            }
            catch (Exception)
            {
                return Task.FromResult(InvalidResSup("Invalid timeout"));
            }
        }

        private static Task<Response> Shutdown(Request req)
        {
            req.Headers.TryGetValue("timeout", out var ts);
            try
            {
                var t = Convert.ToInt32(ts ?? "0");
                return Services.Shutdown(new TimeSpan(0, 0, 0, 0, t));
            }
            catch (Exception)
            {
                return Task.FromResult(InvalidResSup("Invalid timeout"));
            }
        }
    }
}