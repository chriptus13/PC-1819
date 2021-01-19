using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using JsonEchoServer;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace PC_1819i_S3
{
    public class Client
    {
        private static readonly IPAddress Server = IPAddress.Loopback;
        private const int Port = 8081;
        private static readonly JsonSerializer Serializer = new JsonSerializer();

        // Remove '1'
        public static async Task Main1(string[] args)
        {
            bool exit = false;
            do
            {
                PrintMenu();
                char input = Convert.ToChar(Console.Read());
                Response resp = null;
                switch (input)
                {
                    case '1':
                        resp = await Create();
                        break;
                    case '2':
                        resp = await Send();
                        break;
                    case '3':
                        resp = await Receive();
                        break;
                    case '4':
                        resp = await Shutdown();
                        break;
                    case '5':
                        exit = true;
                        break;
                    default:
                        Console.WriteLine("Invalid option!");
                        break;
                }

                if (resp != null)
                    Console.WriteLine(
                        $"Response: \nStatus={resp.Status}\nHeaders={resp.Headers}\nPayload={resp.Payload}");
            } while (!exit);
        }

        private static void PrintMenu()
        {
            Console.WriteLine("1 - CREATE");
            Console.WriteLine("2 - SEND");
            Console.WriteLine("3 - RECEIVE");
            Console.WriteLine("4 - SHUTDOWN");
            Console.WriteLine("5 - Exit");
            Console.Write("> ");
        }

        private static Task<Response> Create()
        {
            Console.WriteLine("Path?");
            string path = Console.ReadLine();
            var req = new Request()
            {
                Method = "CREATE",
                Path = path
            };
            return ProcessRequest(req);
        }

        private static Task<Response> Send()
        {
            Console.WriteLine("Path?");
            string path = Console.ReadLine();
            Console.WriteLine("Message?");
            string msg = Console.ReadLine();
            var req = new Request()
            {
                Method = "SEND",
                Path = path,
                Payload = new JObject()
                {
                    {"msg", msg}
                }
            };
            return ProcessRequest(req);
        }

        private static Task<Response> Receive()
        {
            Console.WriteLine("Path?");
            string path = Console.ReadLine();
            Console.WriteLine("Timeout?");
            string timeout = Console.ReadLine();

            var req = new Request()
            {
                Method = "RECEIVE",
                Path = path,
                Headers = new JObject()
                {
                    {"timeout", timeout}
                }
            };
            return ProcessRequest(req);
        }

        private static Task<Response> Shutdown()
        {
            Console.WriteLine("Timeout?");
            string timeout = Console.ReadLine();

            var req = new Request()
            {
                Method = "RECEIVE",
                Headers = new JObject()
                {
                    {"timeout", timeout}
                }
            };
            return ProcessRequest(req);
        }


        private static async Task<Response> ProcessRequest(Request req)
        {
            var client = new TcpClient();
            client.Connect(Server, Port);
            using (client)
            {
                var stream = client.GetStream();
                var reader = new JsonTextReader(new StreamReader(stream))
                {
                    // To support reading multiple top-level objects
                    SupportMultipleContent = true
                };
                var writer = new JsonTextWriter(new StreamWriter(stream));
                Serializer.Serialize(writer, req);
                await writer.FlushAsync();

                while (true)
                {
                    try
                    {
                        // to consume any bytes until start of object ('{')
                        do
                        {
                            await reader.ReadAsync();
                        } while (reader.TokenType != JsonToken.StartObject
                                 && reader.TokenType != JsonToken.None);

                        if (reader.TokenType == JsonToken.None)
                        {
                            return null;
                        }

                        var json = await JObject.LoadAsync(reader);
                        // to ensure that proper deserialization is possible
                        var resp = json.ToObject<Response>();
                        return resp;
                    }
                    catch (Exception e)
                    {
                        Console.WriteLine($"Unexpected exception, closing connection {e.Message}");
                        return null;
                    }
                }
            }
        }
    }
}