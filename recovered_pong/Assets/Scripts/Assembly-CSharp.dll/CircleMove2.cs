// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class CircleMove2 : MonoBehaviour
{
    public GameObject Circle;

    // RVA: 0x263c
    private Vector3 RandomVector(float min, float max)
    {
        float local0 = Random.Range(min, max);
        float local1 = Random.Range(min, max);
        return new Vector3(Random.Range(min, max), local0, local1);
    }

    // RVA: 0x2667
    private void Start()
    {
        this.GetComponent<Rigidbody2D>().velocity = this.RandomVector(-10.0f, 10.0f);
    }

    // RVA: 0x268c
    private void Update()
    {
        float local0 = this.transform.position.x;
        if (local0 < -10.0f) {
            if (local0 > -11.0f) {
                SceneManager.LoadScene("YouLose");
            }
        }
    }

}
