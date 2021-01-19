using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace Serie3_Exc2 {
    class Services {
        internal async static Task Search(string path, string str, Action<string, int, string> print, CancellationToken ct) {

            string[] allFiles = await Task.Run(() => Directory.GetFiles(path), ct);

            Task<int>[] tasks = new Task<int>[allFiles.Length];

            object mon = new object();
            var opt = new ParallelOptions() { CancellationToken = ct };
            Parallel.For(0, allFiles.Length, opt, i => {
                string fileName = allFiles[i];
                var tcs = new TaskCompletionSource<int>();
                ProcessFile(fileName, str, print, tcs, ct);
                lock(mon) {
                    tasks[i] = tcs.Task;
                }
            });

            await Task.WhenAll(tasks);
        }


        private static async void ProcessFile(string filename, string strToFind, Action<string, int, string> print, TaskCompletionSource<int> tcs, CancellationToken ct) {
            using(var reader = File.OpenText(filename)) {
                string str;
                int line = 1;
                while((str = await reader.ReadLineAsync()) != null) {
                    if(ct.IsCancellationRequested) {
                        tcs.SetCanceled();
                        return;
                    }
                    ct.ThrowIfCancellationRequested();
                    if(str.Contains(strToFind)) {
                        print(filename, line, str);
                    }
                    line++;
                }
                tcs.SetResult(line);
            }
        }
    }
}
