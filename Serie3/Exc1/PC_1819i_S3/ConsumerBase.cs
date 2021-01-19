using System.Threading;
using System.Threading.Tasks;

namespace PC_1819i_S3
{
    public class ConsumerBase<T> : TaskCompletionSource<T>
    {
        private int _acquired = 0;

        public bool TryAcquire()
        {
            return Interlocked.CompareExchange(ref _acquired, 1, 0) == 0;
        }
    }
}