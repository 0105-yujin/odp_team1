import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface ApiService {
    @POST("sensor/opensrc/test/")
    Call<SensorResponse> sendSensorData(@Body SensorRequest request);
}