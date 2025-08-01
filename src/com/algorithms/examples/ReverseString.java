package com.algorithms.examples;

public class ReverseString {

	/**
	 * Reverses a string using StringBuilder
	 * Time Complexity: O(n)
	 * Space Complexity: O(n)
	 */
	public String reverseWithStringBuilder(String str) {
		if (str == null || str.length() <= 1) {
			return str;
		}
		
		StringBuilder reversed = new StringBuilder(str);
		return reversed.reverse().toString();
	}

	/**
	 * Reverses a string using character array and two pointers
	 * Time Complexity: O(n)
	 * Space Complexity: O(n)
	 */
	public String reverseWithTwoPointers(String str) {
		if (str == null || str.length() <= 1) {
			return str;
		}
		
		char[] chars = str.toCharArray();
		int left = 0;
		int right = chars.length - 1;
		
		while (left < right) {
			char temp = chars[left];
			chars[left] = chars[right];
			chars[right] = temp;
			left++;
			right--;
		}
		
		return new String(chars);
	}

	/**
	 * Reverses a string using recursion
	 * Time Complexity: O(n)
	 * Space Complexity: O(n) due to call stack
	 */
	public String reverseWithRecursion(String str) {
		if (str == null || str.length() <= 1) {
			return str;
		}
		
		return reverseWithRecursion(str.substring(1)) + str.charAt(0);
	}

	/**
	 * Reverses a string using a simple loop
	 * Time Complexity: O(n)
	 * Space Complexity: O(n)
	 */
	public String reverseWithLoop(String str) {
		if (str == null || str.length() <= 1) {
			return str;
		}
		
		String reversed = "";
		for (int i = str.length() - 1; i >= 0; i--) {
			reversed += str.charAt(i);
		}
		
		return reversed;
	}

	public static void main(String[] args) {
		ReverseString rs = new ReverseString();
		
		String[] testStrings = {
			"hello",
			"world",
			"Java",
			"algorithm",
			"a",
			"",
			"12345",
			"Hello World!"
		};
		
		System.out.println("=== Reverse String Examples ===\n");
		
		for (String test : testStrings) {
			System.out.println("Original: \"" + test + "\"");
			System.out.println("StringBuilder: \"" + rs.reverseWithStringBuilder(test) + "\"");
			System.out.println("Two Pointers:  \"" + rs.reverseWithTwoPointers(test) + "\"");
			System.out.println("Recursion:     \"" + rs.reverseWithRecursion(test) + "\"");
			System.out.println("Loop:          \"" + rs.reverseWithLoop(test) + "\"");
			System.out.println();
		}
		
		// Performance demonstration
		System.out.println("=== Performance Test ===");
		String longString = "This is a longer string to test performance differences between methods";
		
		long startTime = System.nanoTime();
		rs.reverseWithStringBuilder(longString);
		long endTime = System.nanoTime();
		System.out.println("StringBuilder method: " + (endTime - startTime) + " nanoseconds");
		
		startTime = System.nanoTime();
		rs.reverseWithTwoPointers(longString);
		endTime = System.nanoTime();
		System.out.println("Two Pointers method:  " + (endTime - startTime) + " nanoseconds");
	}
}