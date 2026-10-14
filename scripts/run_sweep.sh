HOST=${1:-localhost}
PORT=${2:-9091}
OUTPUT=${3:-results/threadpool_rps.csv}
echo "Starting ThreadPoolServer on port $PORT with pool size 50..."
java -cp out server.ThreadPoolServer $PORT 50 &
SERVER_PID=$!
sleep 2  # wait for server to start
echo "Running sweep against $HOST:$PORT..."
java -cp out benchmark.SweepRunner $HOST $PORT $OUTPUT
echo "Stopping server..."
kill $SERVER_PID
echo "Done. Results written to $OUTPUT"
echo "Generate plots with: python scripts/plot_results.py"