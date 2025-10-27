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
	start := time.Now()

	logger.GetLogger().Infof("gRPC call: %s started", info.FullMethod)

	resp, err := handler(ctx, req)

	duration := time.Since(start)

	if err != nil {
		logger.GetLogger().Errorf("gRPC call: %s failed in %v: %v", info.FullMethod, duration, err)
	} else {
		logger.GetLogger().Infof("gRPC call: %s completed in %v", info.FullMethod, duration)
	}

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
	defer func() {
		if r := recover(); r != nil {
			logger.GetLogger().Errorf("Panic recovered in %s: %v", info.FullMethod, r)
			err = status.Errorf(codes.Internal, "internal server error")
		}
	}()

	return handler(ctx, req)
}

// TimeoutInterceptor adds timeout to gRPC calls
func TimeoutInterceptor(timeout time.Duration) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		ctx, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()

		return handler(ctx, req)
	}
}
