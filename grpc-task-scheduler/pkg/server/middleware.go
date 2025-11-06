package server

import (
	"context"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"scheduler-grpc-server/pkg/logger"
	"scheduler-grpc-server/pkg/metrics"
)

// LoggingInterceptor logs all gRPC requests
func LoggingInterceptor(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
	// [DEBUG] Entry point for LoggingInterceptor
	start := time.Now()
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-ENTRY] LoggingInterceptor called for method: %s", info.FullMethod)

	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-START] gRPC call: %s started", info.FullMethod)
	logger.GetLogger().Infof("gRPC call: %s started", info.FullMethod)

	// [DEBUG] About to call handler
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-HANDLER-BEFORE] About to call handler for %s", info.FullMethod)
	resp, err := handler(ctx, req)
	// [DEBUG] Handler returned
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-HANDLER-AFTER] Handler returned for %s (error=%v)", info.FullMethod, err != nil)

	duration := time.Since(start)

	if err != nil {
		// [DEBUG] Call failed
		logger.GetLogger().Errorf("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-ERROR] gRPC call: %s failed in %v: %v", info.FullMethod, duration, err)
		logger.GetLogger().Errorf("gRPC call: %s failed in %v: %v", info.FullMethod, duration, err)
	} else {
		// [DEBUG] Call succeeded
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-SUCCESS] gRPC call: %s completed in %v", info.FullMethod, duration)
		logger.GetLogger().Infof("gRPC call: %s completed in %v", info.FullMethod, duration)
	}

	// [DEBUG] About to return from interceptor
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-LOGGING-EXIT] LoggingInterceptor returning for method: %s (duration: %v)", info.FullMethod, duration)
	return resp, err
}

// MetricsInterceptor collects metrics for gRPC requests
func MetricsInterceptor(metrics *metrics.InMemoryMetrics) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		start := time.Now()

		resp, err := handler(ctx, req)

		duration := time.Since(start)
		metrics.RecordResponseTime(duration)

		if err != nil {
			metrics.IncrementFailedRequests()
		} else {
			metrics.IncrementSuccessfulRequests()
		}

		return resp, err
	}
}

// RecoveryInterceptor recovers from panics in gRPC handlers
func RecoveryInterceptor(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (resp interface{}, err error) {
	// [DEBUG] Entry point for RecoveryInterceptor
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-RECOVERY-ENTRY] RecoveryInterceptor called for method: %s", info.FullMethod)
	defer func() {
		if r := recover(); r != nil {
			// [DEBUG] Panic recovered
			logger.GetLogger().Errorf("[DEBUG] [GRPC-INTERCEPTOR-RECOVERY-PANIC] Panic recovered in %s: %v", info.FullMethod, r)
			logger.GetLogger().Errorf("Panic recovered in %s: %v", info.FullMethod, r)
			err = status.Errorf(codes.Internal, "internal server error")
		}
	}()

	// [DEBUG] About to call handler
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-RECOVERY-HANDLER-BEFORE] About to call handler for %s", info.FullMethod)
	resp, err = handler(ctx, req)
	// [DEBUG] Handler returned
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-RECOVERY-HANDLER-AFTER] Handler returned for %s (error=%v)", info.FullMethod, err != nil)
	logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-RECOVERY-EXIT] RecoveryInterceptor returning for method: %s", info.FullMethod)
	return resp, err
}

// TimeoutInterceptor adds timeout to gRPC calls
func TimeoutInterceptor(timeout time.Duration) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		// [DEBUG] Entry point for TimeoutInterceptor
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-ENTRY] TimeoutInterceptor called for method: %s (timeout: %v)", info.FullMethod, timeout)
		
		// [DEBUG] Creating timeout context
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-CTX-BEFORE] Creating timeout context with timeout: %v", timeout)
		ctx, cancel := context.WithTimeout(ctx, timeout)
		// [DEBUG] Timeout context created
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-CTX-AFTER] Timeout context created")
		defer func() {
			// [DEBUG] About to cancel timeout context
			logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-CANCEL] Cancelling timeout context for method: %s", info.FullMethod)
			cancel()
			// [DEBUG] Timeout context cancelled
			logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-CANCELLED] Timeout context cancelled for method: %s", info.FullMethod)
		}()

		// [DEBUG] About to call handler
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-HANDLER-BEFORE] About to call handler for %s", info.FullMethod)
		resp, err := handler(ctx, req)
		// [DEBUG] Handler returned
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-HANDLER-AFTER] Handler returned for %s (error=%v, ctx.Err=%v)", 
			info.FullMethod, err != nil, ctx.Err())
		
		// [DEBUG] Check if context timed out
		if ctx.Err() == context.DeadlineExceeded {
			logger.GetLogger().Errorf("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-DEADLINE] Request timeout exceeded for method: %s", info.FullMethod)
		}
		
		// [DEBUG] About to return from interceptor
		logger.GetLogger().Infof("[DEBUG] [GRPC-INTERCEPTOR-TIMEOUT-EXIT] TimeoutInterceptor returning for method: %s", info.FullMethod)
		return resp, err
	}
}
