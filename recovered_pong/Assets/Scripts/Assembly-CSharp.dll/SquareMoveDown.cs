// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class SquareMoveDown : MonoBehaviour
{
    public GameObject Square;
    public float speed;
    private bool AllowSquareDown;
    public Button DownButton;

    // RVA: 0x2703
    private void Start()
    {
    }

    // RVA: 0x2708
    private void Update()
    {
        this.DownButton.onClick.AddListener(new UnityAction(this.DownClick));
        this.DownButton.onClick.AddListener(new UnityAction(this.DownClick));
        if (this.transform.position.y > -4.0f) {
            this.AllowSquareDown = true;
        }
        if (this.transform.position.y < -4.0f) {
            this.AllowSquareDown = false;
        }
    }

    // RVA: 0x277c
    private void DownClick()
    {
        bool local0 = true;
        this.AllowSquareDown = true;
        if (local0) {
            float local1 = Input.GetAxis("Horizontal");
            float local2 = Input.GetAxis("Vertical") - 1.0f;
            this.Square.transform.Translate(new Vector3(local1, local2, 0.0f) * this.speed * Time.deltaTime);
        }
    }

}
